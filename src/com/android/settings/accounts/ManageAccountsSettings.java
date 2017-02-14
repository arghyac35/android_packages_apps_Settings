/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.accounts;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.ActionBar;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.SyncAdapterType;
import android.content.SyncInfo;
import android.content.SyncStatusInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.UserHandle;
import android.support.annotation.VisibleForTesting;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceScreen;
import android.util.ArraySet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settings.R;
import com.android.settings.SettingsActivity;
import com.android.settings.Utils;
import com.android.settingslib.accounts.AuthenticatorHelper;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static android.content.Intent.EXTRA_USER;

/** Manages settings for Google Account. */
public class ManageAccountsSettings extends AccountPreferenceBase
        implements AuthenticatorHelper.OnAccountsUpdateListener {
    public static final String KEY_ACCOUNT_TYPE = "account_type";
    public static final String KEY_ACCOUNT_LABEL = "account_label";

    private static final int MENU_SYNC_NOW_ID = Menu.FIRST;
    private static final int MENU_SYNC_CANCEL_ID = Menu.FIRST + 1;

    private static final int REQUEST_SHOW_SYNC_SETTINGS = 1;

    private String[] mAuthorities;
    private TextView mErrorInfoView;

    // If an account type is set, then show only accounts of that type
    private String mAccountType;
    // Temporary hack, to deal with backward compatibility
    // mFirstAccount is used for the injected preferences
    private Account mFirstAccount;

    protected Set<String> mUserFacingSyncAuthorities;

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.ACCOUNTS_MANAGE_ACCOUNTS;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        Bundle args = getArguments();
        if (args != null && args.containsKey(KEY_ACCOUNT_TYPE)) {
            mAccountType = args.getString(KEY_ACCOUNT_TYPE);
        }
        addPreferencesFromResource(R.xml.manage_accounts_settings);
        setHasOptionsMenu(true);
    }

    @Override
    public void onResume() {
        super.onResume();
        mAuthenticatorHelper.listenToAccountUpdates();
        updateAuthDescriptions();
        showAccountsIfNeeded();
        showSyncState();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        final View view = inflater.inflate(R.layout.manage_accounts_screen, container, false);
        final ViewGroup prefs_container = (ViewGroup) view.findViewById(R.id.prefs_container);
        Utils.prepareCustomPreferencesList(container, view, prefs_container, false);
        View prefs = super.onCreateView(inflater, prefs_container, savedInstanceState);
        prefs_container.addView(prefs);
        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        final Activity activity = getActivity();
        final View view = getView();

        mErrorInfoView = (TextView) view.findViewById(R.id.sync_settings_error_info);
        mErrorInfoView.setVisibility(View.GONE);

        mAuthorities = activity.getIntent().getStringArrayExtra(AUTHORITIES_FILTER_KEY);

        Bundle args = getArguments();
        if (args != null && args.containsKey(KEY_ACCOUNT_LABEL)) {
            getActivity().setTitle(args.getString(KEY_ACCOUNT_LABEL));
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mAuthenticatorHelper.stopListeningToAccountUpdates();
    }

    @Override
    public void onStop() {
        super.onStop();
        final Activity activity = getActivity();
        activity.getActionBar().setDisplayOptions(0, ActionBar.DISPLAY_SHOW_CUSTOM);
        activity.getActionBar().setCustomView(null);
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (preference instanceof AccountPreference) {
            startAccountSettings((AccountPreference) preference);
        } else {
            return false;
        }
        return true;
    }

    private void startAccountSettings(AccountPreference acctPref) {
        Bundle args = new Bundle();
        args.putParcelable(AccountSyncSettings.ACCOUNT_KEY, acctPref.getAccount());
        args.putParcelable(EXTRA_USER, mUserHandle);
        ((SettingsActivity) getActivity()).startPreferencePanel(this,
                AccountSyncSettings.class.getCanonicalName(), args,
                R.string.account_sync_settings_title, acctPref.getAccount().name,
                this, REQUEST_SHOW_SYNC_SETTINGS);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.add(0, MENU_SYNC_NOW_ID, 0, getString(R.string.sync_menu_sync_now))
                .setIcon(R.drawable.ic_menu_refresh_holo_dark);
        menu.add(0, MENU_SYNC_CANCEL_ID, 0, getString(R.string.sync_menu_sync_cancel))
                .setIcon(com.android.internal.R.drawable.ic_menu_close_clear_cancel);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        boolean syncActive = !getCurrentSyncs(mUserHandle.getIdentifier()).isEmpty();
        menu.findItem(MENU_SYNC_NOW_ID).setVisible(!syncActive);
        menu.findItem(MENU_SYNC_CANCEL_ID).setVisible(syncActive);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_SYNC_NOW_ID:
                requestOrCancelSyncForAccounts(true);
                return true;
            case MENU_SYNC_CANCEL_ID:
                requestOrCancelSyncForAccounts(false);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void requestOrCancelSyncForAccounts(boolean sync) {
        final int userId = mUserHandle.getIdentifier();
        SyncAdapterType[] syncAdapters = ContentResolver.getSyncAdapterTypesAsUser(userId);
        Bundle extras = new Bundle();
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
        int count = getPreferenceScreen().getPreferenceCount();
        // For each account
        for (int i = 0; i < count; i++) {
            Preference pref = getPreferenceScreen().getPreference(i);
            if (pref instanceof AccountPreference) {
                Account account = ((AccountPreference) pref).getAccount();
                // For all available sync authorities, sync those that are enabled for the account
                for (int j = 0; j < syncAdapters.length; j++) {
                    SyncAdapterType sa = syncAdapters[j];
                    if (syncAdapters[j].accountType.equals(mAccountType)
                            && ContentResolver.getSyncAutomaticallyAsUser(account, sa.authority,
                            userId)) {
                        if (sync) {
                            ContentResolver.requestSyncAsUser(account, sa.authority, userId,
                                    extras);
                        } else {
                            ContentResolver.cancelSyncAsUser(account, sa.authority, userId);
                        }
                    }
                }
            }
        }
    }

    @Override
    protected void onSyncStateUpdated() {
        final Activity activity = getActivity();
        // Catch any delayed delivery of update messages
        if (activity == null || activity.isFinishing()) {
            return;
        }
        showSyncState();
        activity.invalidateOptionsMenu();
    }

    private void tryInitUserFacingSyncAuthorities(int userId) {
        if (mUserFacingSyncAuthorities != null) {
            return;
        }
        mUserFacingSyncAuthorities = new ArraySet<>();

        // only track userfacing sync adapters when deciding if account is synced or not
        final SyncAdapterType[] syncAdapters = ContentResolver.getSyncAdapterTypesAsUser(userId);
        for (int k = 0, n = syncAdapters.length; k < n; k++) {
            final SyncAdapterType sa = syncAdapters[k];
            if (sa.isUserVisible()) {
                mUserFacingSyncAuthorities.add(sa.authority);
            }
        }
    }

    /**
     * Shows the sync state of the accounts. Note: it must be called after the accounts have been
     * loaded.
     *
     * @see {@link #showAccountsIfNeeded()}.
     */
    @VisibleForTesting
    void showSyncState() {
        final int userId = mUserHandle.getIdentifier();
        tryInitUserFacingSyncAuthorities(userId);

        // iterate over all the preferences, setting the state properly for each
        final List<SyncInfo> currentSyncs = getCurrentSyncs(userId);

        boolean anySyncFailed = false; // true if sync on any account failed
        Date date = new Date();

        final PreferenceScreen screen = getPreferenceScreen();
        final int prefCount = screen.getPreferenceCount();
        for (int i = 0; i < prefCount; i++) {
            Preference pref = screen.getPreference(i);
            if (!(pref instanceof AccountPreference)) {
                continue;
            }

            final AccountPreference accountPref = (AccountPreference) pref;
            final Account account = accountPref.getAccount();
            int syncCount = 0;
            long lastSuccessTime = 0;
            boolean syncIsFailing = false;
            final ArrayList<String> authorities = accountPref.getAuthorities();
            boolean syncingNow = false;
            if (authorities != null) {
                for (String authority : authorities) {
                    SyncStatusInfo status = getSyncStatusInfo(account, authority, userId);
                    boolean syncEnabled = isSyncEnabled(userId, account, authority);
                    boolean activelySyncing = isSyncing(currentSyncs, account, authority);
                    boolean lastSyncFailed = status != null
                            && syncEnabled
                            && status.lastFailureTime != 0
                            && status.getLastFailureMesgAsInt(0)
                            != ContentResolver.SYNC_ERROR_SYNC_ALREADY_IN_PROGRESS;
                    if (lastSyncFailed && !activelySyncing
                            && !ContentResolver.isSyncPending(account, authority)) {
                        syncIsFailing = true;
                        anySyncFailed = true;
                        break;
                    }

                    if (status != null && lastSuccessTime < status.lastSuccessTime) {
                        lastSuccessTime = status.lastSuccessTime;
                    }
                    syncCount += syncEnabled && mUserFacingSyncAuthorities.contains(authority)
                            ? 1 : 0;
                    syncingNow |= activelySyncing;
                    if (syncingNow) {
                        break;
                    }
                }
            } else {
                if (VERBOSE) {
                    Log.v(TAG, "no syncadapters found for " + account);
                }
            }
            if (syncIsFailing) {
                accountPref.setSyncStatus(AccountPreference.SYNC_ERROR, true);
            } else if (syncCount == 0) {
                accountPref.setSyncStatus(AccountPreference.SYNC_DISABLED, true);
            } else if (syncCount > 0) {
                if (syncingNow) {
                    accountPref.setSyncStatus(AccountPreference.SYNC_IN_PROGRESS, true);
                } else {
                    accountPref.setSyncStatus(AccountPreference.SYNC_ENABLED, true);
                    if (lastSuccessTime > 0) {
                        accountPref.setSyncStatus(AccountPreference.SYNC_ENABLED, false);
                        date.setTime(lastSuccessTime);
                        final String timeString = formatSyncDate(date);
                        accountPref.setSummary(getResources().getString(
                                R.string.last_synced, timeString));
                    }
                }
            } else {
                accountPref.setSyncStatus(AccountPreference.SYNC_DISABLED, true);
            }
        }
        if (mErrorInfoView != null) {
            mErrorInfoView.setVisibility(anySyncFailed ? View.VISIBLE : View.GONE);
        }
    }

    private boolean isSyncing(List<SyncInfo> currentSyncs, Account account, String authority) {
        final int count = currentSyncs.size();
        for (int i = 0; i < count; i++) {
            SyncInfo syncInfo = currentSyncs.get(i);
            if (syncInfo.account.equals(account) && syncInfo.authority.equals(authority)) {
                return true;
            }
        }
        return false;
    }

    @VisibleForTesting
    protected boolean isSyncEnabled(int userId, Account account, String authority) {
        return ContentResolver.getSyncAutomaticallyAsUser(account, authority, userId)
                && ContentResolver.getMasterSyncAutomaticallyAsUser(userId)
                && (ContentResolver.getIsSyncableAsUser(account, authority, userId) > 0);
    }

    @Override
    public void onAccountsUpdate(UserHandle userHandle) {
        showAccountsIfNeeded();
        onSyncStateUpdated();
    }

    private void showAccountsIfNeeded() {
        if (getActivity() == null) return;
        Account[] accounts = AccountManager.get(getActivity()).getAccountsAsUser(
                mUserHandle.getIdentifier());
        getPreferenceScreen().removeAll();
        mFirstAccount = null;
        addPreferencesFromResource(R.xml.manage_accounts_settings);
        for (int i = 0, n = accounts.length; i < n; i++) {
            final Account account = accounts[i];
            // If an account type is specified for this screen, skip other types
            if (mAccountType != null && !account.type.equals(mAccountType)) continue;
            final ArrayList<String> auths = getAuthoritiesForAccountType(account.type);

            if (AccountRestrictionHelper.showAccount(mAuthorities, auths)) {
                final Drawable icon = getDrawableForType(account.type);
                final AccountPreference preference =
                        new AccountPreference(getPrefContext(), account, icon, auths, false);
                getPreferenceScreen().addPreference(preference);
                if (mFirstAccount == null) {
                    mFirstAccount = account;
                }
            }
        }
        if (mAccountType != null && mFirstAccount != null) {
            addAuthenticatorSettings();
        } else {
            // There's no account, close activity
            finish();
        }
    }

    private void addAuthenticatorSettings() {
        PreferenceScreen prefs = addPreferencesForType(mAccountType, getPreferenceScreen());
        if (prefs != null) {
            mAccountTypePreferenceLoader.updatePreferenceIntents(prefs, mAccountType, mFirstAccount);
        }
    }

    @Override
    protected void onAuthDescriptionsUpdated() {
        // Update account icons for all account preference items
        for (int i = 0; i < getPreferenceScreen().getPreferenceCount(); i++) {
            Preference pref = getPreferenceScreen().getPreference(i);
            if (pref instanceof AccountPreference) {
                AccountPreference accPref = (AccountPreference) pref;
                accPref.setSummary(getLabelForType(accPref.getAccount().type));
            }
        }
    }

    @VisibleForTesting
    protected List<SyncInfo> getCurrentSyncs(int userId) {
        return ContentResolver.getCurrentSyncsAsUser(userId);
    }

    @VisibleForTesting
    protected SyncStatusInfo getSyncStatusInfo(Account account, String authority, int userId) {
        return ContentResolver.getSyncStatusAsUser(account, authority, userId);
    }
}
