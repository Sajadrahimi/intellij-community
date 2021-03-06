/*
 * Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
 */
package com.intellij.ide.gdpr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.openapi.application.ApplicationNamesInfo;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Eugene Zhuravlev
 * Date: 05-Dec-17
 */
public class ConsentOptions {
  private static final Logger LOG = Logger.getInstance("#com.intellij.ide.gdpr.ConsentOptions");
  
  private static final String BUNDLED_RESOURCE_PATH = "/consents.json";
  private static final String DEFAULTS_PATH = ApplicationNamesInfo.getInstance().getLowercaseProductName() + "/consentOptions/cached";
  private static final String CONFIRMED_PATH = "/consentOptions/accepted";

  private static final String STATISTICS_OPTION_ID = "rsch.send.usage.stat";

  // here we have some well-known consents
  public enum Permission {
    YES, NO, UNDEFINED
  }

  public static Permission isSendingUsageStatsAllowed() {
    final ConfirmedConsent confirmedConsent = getConfirmedConsent(STATISTICS_OPTION_ID);
    return confirmedConsent == null? Permission.UNDEFINED : confirmedConsent.isAccepted()? Permission.YES : Permission.NO;
  }

  @Nullable
  public static String getConfirmedConsentsString() {
    final Map<String, Consent> defaults = loadDefaultConsents();
    if (!defaults.isEmpty()) {
      final String str = confirmedConsentToExternalString(
        loadConfirmedConsents().values().stream().filter(c -> {
          final Consent def = defaults.get(c.getId());
          return def != null && !def.isDeleted();
        })
      );
      return StringUtil.isEmptyOrSpaces(str)? null : str;
    }
    return null;
  }

  public static void applyServerUpdates(@Nullable String json) {
    if (StringUtil.isEmptyOrSpaces(json)) {
      return;
    }
    try {
      final Collection<ConsentAttributes> fromServer = fromJson(json);
      // defaults
      final Map<String, Consent> defaults = loadDefaultConsents();
      if (applyServerChangesToDefaults(defaults, fromServer)) {
        FileUtil.writeToFile(getDefaultConsentsFile(), consentsToJson(defaults.values().stream()));
      }
      // confirmed consents
      final Map<String, ConfirmedConsent> confirmed = loadConfirmedConsents();
      if (applyServerChangesToConfirmedConsents(confirmed, fromServer)) {
        FileUtil.writeToFile(
          getConfirmedConsentsFile(),
          confirmedConsentToExternalString(confirmed.values().stream())
        );
      }
    }
    catch (Exception e) {
      LOG.info(e);
    }
  }

  public static Pair<Collection<Consent>, Boolean> getConsents() {
    final Map<String, Consent> allDefaults = loadDefaultConsents();
    if (allDefaults.isEmpty()) {
      return Pair.create(Collections.emptyList(), Boolean.FALSE);
    }
    final Map<String, ConfirmedConsent> allConfirmed = loadConfirmedConsents();
    final List<Consent> result = new ArrayList<>();
    for (Map.Entry<String, Consent> entry : allDefaults.entrySet()) {
      final Consent base = entry.getValue();
      if (!base.isDeleted()) {
        final ConfirmedConsent confirmed = allConfirmed.get(base.getId());
        result.add(confirmed == null? base : base.derive(confirmed.isAccepted()));
      }
    }
    Collections.sort(result, Comparator.comparing(o -> o.getName()));
    return Pair.create(result, needReconfirm(allDefaults, allConfirmed));
  }

  public static void setConsents(Collection<Consent> confirmedByUser) {
    saveConfirmedConsents(
      confirmedByUser.stream().map(
        c -> new ConfirmedConsent(c.getId(), c.getVersion(), c.isAccepted(), 0L)
      ).collect(Collectors.toList())
    );
  }

  @Nullable
  private static ConfirmedConsent getConfirmedConsent(String consentId) {
    final Consent defConsent = loadDefaultConsents().get(consentId);
    if (defConsent != null && defConsent.isDeleted()) {
      return null;
    }
    return loadConfirmedConsents().get(consentId);
  }

  private static void saveConfirmedConsents(@NotNull Collection<ConfirmedConsent> updates) {
    if (!updates.isEmpty()) {
      try {
        final Map<String, ConfirmedConsent> allConfirmed = loadConfirmedConsents();
        final long stamp = System.currentTimeMillis();
        for (ConfirmedConsent consent : updates) {
          consent.setAcceptanceTime(stamp);
          allConfirmed.put(consent.getId(), consent);
        }
        FileUtil.writeToFile(
          getConfirmedConsentsFile(),
          confirmedConsentToExternalString(allConfirmed.values().stream())
        );
      }
      catch (IOException e) {
        LOG.info(e);
      }
    }
  }

  private static boolean needReconfirm(Map<String, Consent> defaults, Map<String, ConfirmedConsent> confirmed) {
    for (Consent defConsent : defaults.values()) {
      if (defConsent.isDeleted()) {
        continue;
      }
      final ConfirmedConsent confirmedConsent = confirmed.get(defConsent.getId());
      if (confirmedConsent == null) {
        return true;
      }
      final Version confirmedVersion = confirmedConsent.getVersion();
      final Version defaultVersion = defConsent.getVersion();
      // consider only major version differences
      if (confirmedVersion.isOlder(defaultVersion) && confirmedVersion.getMajor() != defaultVersion.getMajor()) {
        return true;
      }
    }
    return false;
  }

  private static File getDefaultConsentsFile() {
    return new File(Locations.getDataRoot(), DEFAULTS_PATH);
  }

  private static File getConfirmedConsentsFile() {
    return new File(Locations.getDataRoot(), CONFIRMED_PATH);
  }

  private static boolean applyServerChangesToConfirmedConsents(Map<String, ConfirmedConsent> base, Collection<ConsentAttributes> fromServer) {
    boolean changes = false;
    for (ConsentAttributes update : fromServer) {
      final ConfirmedConsent current = base.get(update.consentId);
      if (current != null) {
        final ConfirmedConsent change = new ConfirmedConsent(update);
        if (!change.getVersion().isOlder(current.getVersion()) && current.getAcceptanceTime() < update.acceptanceTime) {
          base.put(change.getId(), change);
          changes = true;
        }
      }
    }
    return changes;
  }

  private static boolean applyServerChangesToDefaults(Map<String, Consent> base, Collection<ConsentAttributes> fromServer) {
    boolean changes = false;
    for (ConsentAttributes update : fromServer) {
      final Consent newConsent = new Consent(update);
      final Consent current = base.get(newConsent.getId());
      if (current == null || newConsent.getVersion().isNewer(current.getVersion()) || newConsent.isDeleted() != current.isDeleted()) {
        base.put(newConsent.getId(), newConsent);
        changes = true;
      }
    }
    return changes;
  }

  @NotNull
  private static Collection<ConsentAttributes> fromJson(String json) {
    final ConsentAttributes[] data = StringUtil.isEmptyOrSpaces(json)? null : new GsonBuilder().disableHtmlEscaping().create().fromJson(json, ConsentAttributes[].class);
    return data != null ? Arrays.asList(data) : Collections.emptyList();
  }

  private static String consentsToJson(Stream<Consent> consents) {
    return consentAttributesToJson(consents.map(consent -> consent.toConsentAttributes()));
  }
  
  private static String consentAttributesToJson(Stream<ConsentAttributes> attributes) {
    final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    return gson.toJson(attributes.toArray());
  }
  
  private static String confirmedConsentToExternalString(Stream<ConfirmedConsent> consents) {
    return StringUtil.join(consents.map(c -> c.toExternalString()).collect(Collectors.toList()), ";");
  }

  @NotNull
  private static Map<String, Consent> loadDefaultConsents() {
    final Map<String, Consent> result = new HashMap<>();
    for (ConsentAttributes attributes : fromJson(loadText(ConsentOptions.class.getResourceAsStream(BUNDLED_RESOURCE_PATH)))) {
      result.put(attributes.consentId, new Consent(attributes));
    }
    try {
      applyServerChangesToDefaults(result, fromJson(loadText(new FileInputStream(getDefaultConsentsFile()))));
    }
    catch (FileNotFoundException ignored) {
    }
    return result;
  }

  @NotNull
  private static Map<String, ConfirmedConsent> loadConfirmedConsents() {
    final Map<String, ConfirmedConsent> result = new HashMap<>();
    try {
      final StringTokenizer tokenizer = new StringTokenizer(loadText(new FileInputStream(getConfirmedConsentsFile())), ";", false);
      while (tokenizer.hasMoreTokens()) {
        final ConfirmedConsent consent = ConfirmedConsent.fromString(tokenizer.nextToken());
        if (consent != null) {
          result.put(consent.getId(), consent);
        }
      }
    }
    catch (FileNotFoundException ignored) {
    }
    return result;
  }

  @NotNull
  private static String loadText(InputStream stream) {
    try {
      if (stream != null) {
        final Reader reader = new InputStreamReader(new BufferedInputStream(stream), StandardCharsets.UTF_8);
        try {
          return new String(FileUtil.adaptiveLoadText(reader));
        }
        finally {
          reader.close();
        }
      }
    }
    catch (IOException e) {
      LOG.info(e);
    }
    return "";
  }

}
