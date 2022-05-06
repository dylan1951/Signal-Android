package org.thoughtcrime.securesms.groups;

import androidx.annotation.NonNull;

import org.signal.core.util.logging.Log;
import org.signal.libsignal.zkgroup.VerificationFailedException;
import org.signal.libsignal.zkgroup.auth.AuthCredentialResponse;
import org.signal.libsignal.zkgroup.groups.GroupSecretParams;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.whispersystems.signalservice.api.groupsv2.GroupsV2Api;
import org.whispersystems.signalservice.api.groupsv2.GroupsV2AuthorizationString;
import org.whispersystems.signalservice.api.groupsv2.NoCredentialForRedemptionTimeException;
import org.whispersystems.signalservice.api.push.ServiceId;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class GroupsV2Authorization {

  private static final String TAG = Log.tag(GroupsV2Authorization.class);

  private final ValueCache  aciCache;
  private final ValueCache  pniCache;
  private final GroupsV2Api groupsV2Api;

  public GroupsV2Authorization(@NonNull GroupsV2Api groupsV2Api, @NonNull ValueCache aciCache, @NonNull ValueCache pniCache) {
    this.groupsV2Api = groupsV2Api;
    this.aciCache    = aciCache;
    this.pniCache    = pniCache;
  }

  public GroupsV2AuthorizationString getAuthorizationForToday(@NonNull ServiceId authServiceId,
                                                              @NonNull GroupSecretParams groupSecretParams)
      throws IOException, VerificationFailedException
  {
    boolean    isPni = Objects.equals(authServiceId, SignalStore.account().getPni());
    ValueCache cache = isPni ? pniCache : aciCache;

    return getAuthorizationForToday(authServiceId, cache, groupSecretParams, !isPni);
  }

  private GroupsV2AuthorizationString getAuthorizationForToday(@NonNull ServiceId authServiceId,
                                                               @NonNull ValueCache cache,
                                                               @NonNull GroupSecretParams groupSecretParams,
                                                               boolean isAci)
      throws IOException, VerificationFailedException
  {
    final int today = currentTimeDays();

    Map<Integer, AuthCredentialResponse> credentials = cache.read();

    try {
      return getAuthorization(authServiceId, groupSecretParams, credentials, today);
    } catch (NoCredentialForRedemptionTimeException e) {
      Log.i(TAG, "Auth out of date, will update auth and try again");
      cache.clear();
    } catch (VerificationFailedException e) {
      Log.w(TAG, "Verification failed, will update auth and try again", e);
      cache.clear();
    }

    Log.i(TAG, "Getting new auth credential responses");
    credentials = groupsV2Api.getCredentials(today, isAci);
    cache.write(credentials);

    try {
      return getAuthorization(authServiceId, groupSecretParams, credentials, today);
    } catch (NoCredentialForRedemptionTimeException e) {
      Log.w(TAG, "The credentials returned did not include the day requested");
      throw new IOException("Failed to get credentials");
    }
  }

  public void clear() {
    aciCache.clear();
    pniCache.clear();
  }

  private static int currentTimeDays() {
    return (int) TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis());
  }

  private GroupsV2AuthorizationString getAuthorization(ServiceId authServiceId,
                                                       GroupSecretParams groupSecretParams,
                                                       Map<Integer, AuthCredentialResponse> credentials,
                                                       int today)
      throws NoCredentialForRedemptionTimeException, VerificationFailedException
  {
    AuthCredentialResponse authCredentialResponse = credentials.get(today);

    if (authCredentialResponse == null) {
      throw new NoCredentialForRedemptionTimeException();
    }

    return groupsV2Api.getGroupsV2AuthorizationString(authServiceId, today, groupSecretParams, authCredentialResponse);
  }

  public interface ValueCache {

    void clear();

    @NonNull Map<Integer, AuthCredentialResponse> read();

    void write(@NonNull Map<Integer, AuthCredentialResponse> values);
  }
}
