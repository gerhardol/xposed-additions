package com.spazedog.xposed.additionsgb;

import java.util.ArrayList;
import java.util.List;

import com.spazedog.xposed.additionsgb.DialogBroadcastReceiver.OnDialogListener;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.text.TextUtils;
import android.widget.TextView;
import android.widget.Toast;

public class ActivityRemapButton extends PreferenceActivity implements OnPreferenceClickListener, OnPreferenceChangeListener {
	
	private Integer mKeyCurrent;
	private Integer mKeyPrimary;
	
	private Preference mPrefAddButton;
	private Preference mPrefRemoveButton;
	private ListPreference mPrefOptions;
	private PreferenceCategory mPrefButtons;
	
	private CheckBoxPreference mPrefOnEnaled;
	private ListPreference mPrefOnTap1;
	private ListPreference mPrefOnTap2;
	private ListPreference mPrefOnTap3;
	private ListPreference mPrefOnPress1;
	private ListPreference mPrefOnPress2;
	private ListPreference mPrefOnPress3;
	
	private CheckBoxPreference mPrefOffEnaled;
	private ListPreference mPrefOffTap1;
	private ListPreference mPrefOffTap2;
	private ListPreference mPrefOffTap3;
	private ListPreference mPrefOffPress1;
	private ListPreference mPrefOffPress2;
	private ListPreference mPrefOffPress3;
	
	private PreferenceCategory mCategSleep;
	private PreferenceCategory mCategAwake;
	
	private String[] mOptionsListNames;
	private String[] mOptionsListValues;
	
	private String[] mRemapOnNames;
	private String[] mRemapOnValues;
	
	private String[] mRemapOffNames;
	private String[] mRemapOffValues;
	
	private Boolean mUpdated = false;
	
	private DialogBroadcastReceiver mDialog;
	
	private final static Boolean mAdvancedSettings = false;
	
	@Override
    protected void onCreate(Bundle savedInstanceState) {
    	super.onCreate(savedInstanceState);
    	
    	Boolean isUnlocked = Common.isUnlocked(this);
    	
    	mKeyCurrent = getIntent().getIntExtra("keycode", 0);
    	mKeyPrimary = getIntent().getIntExtra("keyprimary", 0);

    	if (mKeyPrimary > 0) {
    		setTitle(
    				Common.keycodeToString(mKeyPrimary) + 
    				" + " + 
    				Common.keycodeToString( Common.extractKeyCode(mKeyPrimary, mKeyCurrent) )
    		);
    		
    	} else {
    		setTitle(Common.keycodeToString(mKeyCurrent));
    	}
    	
    	addPreferencesFromResource(R.xml.activity_remap_button);
    	
    	Common.loadSharedPreferences(this);
    	
    	String[] onNames = getResources().getStringArray(R.array.remap_actions_on_names);
    	String[] onValues = getResources().getStringArray(R.array.remap_actions_on_values);
    	String[] keyArray = Common.getSharedPreferences(this).getString(Common.Remap.KEY_COLLECTION, "").split(",");
    	List<String> newNames = new ArrayList<String>();
    	List<String> newValues = new ArrayList<String>();
    	
    	for (int i=0; i < onNames.length; i++) {
    		if ((android.os.Build.VERSION.SDK_INT > 10 || (!onValues[i].equals("flipleft") && !onValues[i].equals("flipright"))) &&
    				(android.os.Build.VERSION.SDK_INT > 11 || (!onValues[i].equals("recentapps"))) &&
    				(mAdvancedSettings || (!onValues[i].equals("application") && !onValues[i].equals("keycode") && !onValues[i].equals("intent")))) {
	    		newNames.add(onNames[i]);
	    		newValues.add(onValues[i]);
    		}
    	}
    	
    	/*
    	 * Add user defined keys to the awake list
    	 */
    	for (int i=0; i < keyArray.length; i++) {
    		if (!"".equals(keyArray[i])) {
	    		if (!newValues.contains(keyArray[i])) {
	    			newNames.add( Common.keycodeToString( Integer.parseInt(keyArray[i]) ) );
	        		newValues.add(keyArray[i]);
	    		}
    		}
    	}
   	
    	String[] offNames = getResources().getStringArray(R.array.remap_actions_off_names);
    	String[] offValues = getResources().getStringArray(R.array.remap_actions_off_values);
    	List<String> newOffNames = new ArrayList<String>();
    	List<String> newOffValues = new ArrayList<String>();
    	
    	for (int i=0; i < offNames.length; i++) {
    		if (mAdvancedSettings || (!onValues[i].equals("application") && !offValues[i].equals("keycode") && !offValues[i].equals("intent"))) {
	    		newOffNames.add(offNames[i]);
	    		newOffValues.add(offValues[i]);
    		}
    	}

    	mOptionsListNames = getResources().getStringArray(R.array.remap_options_names);
    	mOptionsListValues = getResources().getStringArray(R.array.remap_options_values);
    	mRemapOnNames = newNames.toArray(new String[newNames.size()]);
    	mRemapOnValues = newValues.toArray(new String[newValues.size()]);
    	mRemapOffNames = newOffNames.toArray(new String[newOffNames.size()]);
    	mRemapOffValues = newOffValues.toArray(new String[newOffValues.size()]);

    	mPrefRemoveButton = (Preference) findPreference("remove_button");
    	mPrefRemoveButton.setOnPreferenceClickListener(this);
    	
    	mPrefAddButton = (Preference) findPreference("add_button");
    	if (isUnlocked && mKeyPrimary == 0) {
    		mPrefAddButton.setOnPreferenceClickListener(this);
    		
    	} else {
    		getPreferenceScreen().removePreference(mPrefAddButton);
    	}
    	
    	mPrefButtons = (PreferenceCategory) findPreference("category_secondary_buttons");
    	if (!isUnlocked || mKeyPrimary > 0) {
    		getPreferenceScreen().removePreference(mPrefButtons);
    		
    	} else {
        	Integer[] keyList = Common.Remap.getKeyList(mKeyCurrent);

        	if (keyList.length > 0) {
	        	for (int i=0; i < keyList.length; i++) {
	        		createKeyPreference(keyList[i]);
	        	}
	        	
        	} else {
        		getPreferenceScreen().removePreference(mPrefButtons);
        	}
    	}
    	
    	mPrefOptions = (ListPreference) findPreference("pref_options");
    	mPrefOptions.setOnPreferenceChangeListener(this);
    	mPrefOptions.setValue("awake");
    	
    	mCategSleep = (PreferenceCategory) findPreference("category_remap_sleep_actions");
    	getPreferenceScreen().removePreference(mCategSleep);
    	
    	mCategAwake = (PreferenceCategory) findPreference("category_remap_awake_actions");

    	mPrefOnEnaled = new CheckBoxPreference(this);
    	mPrefOnEnaled.setKey(Common.Remap.KEY_ON_ENABLED + mKeyCurrent);
    	mPrefOnEnaled.setPersistent(false);
    	mPrefOnEnaled.setTitle(R.string.preference_title_remap_enabled);
    	if (mKeyPrimary > 0 && !Common.Remap.isKeyEnabled(mKeyPrimary, false)) {
    		mPrefOnEnaled.setChecked(false);
    		mPrefOnEnaled.setEnabled(false);
    		
			Toast.makeText(this, R.string.message_primary_disabled_awake, Toast.LENGTH_SHORT).show();
    		
    	} else {
    		mPrefOnEnaled.setChecked(Common.Remap.isKeyEnabled(mKeyCurrent, false));
    	}
    	mCategAwake.addPreference(mPrefOnEnaled);
    	
    	mPrefOnTap1 = new ListPreference(this);
    	mPrefOnTap1.setKey(Common.Remap.KEY_ON_ACTION_TAP1 + mKeyCurrent);
    	mPrefOnTap1.setPersistent(false);
    	mPrefOnTap1.setTitle(R.string.preference_title_remap_tap1);
    	mPrefOnTap1.setEntries(mRemapOnNames);
    	mPrefOnTap1.setEntryValues(mRemapOnValues);
    	mPrefOnTap1.setValue(Common.Remap.getKeyTap(this, mKeyCurrent, Common.Remap.SCREEN_ON, 0));
    	mPrefOnTap1.setOnPreferenceChangeListener(this);
    	mCategAwake.addPreference(mPrefOnTap1);
    	
     	mPrefOnTap2 = new ListPreference(this);
    	mPrefOnTap2.setKey(Common.Remap.KEY_ON_ACTION_TAP2 + mKeyCurrent);
    	mPrefOnTap2.setPersistent(false);
    	mPrefOnTap2.setTitle(R.string.preference_title_remap_tap2);
    	mPrefOnTap2.setEntries(mRemapOnNames);
    	mPrefOnTap2.setEntryValues(mRemapOnValues);
    	mPrefOnTap2.setValue(Common.Remap.getKeyTap(this, mKeyCurrent, Common.Remap.SCREEN_ON, 1));
    	mPrefOnTap2.setOnPreferenceChangeListener(this);
    	if (isUnlocked) {
    		mCategAwake.addPreference(mPrefOnTap2);
    	}
    	
     	mPrefOnTap3 = new ListPreference(this);
    	mPrefOnTap3.setKey(Common.Remap.KEY_ON_ACTION_TAP3 + mKeyCurrent);
    	mPrefOnTap3.setPersistent(false);
    	mPrefOnTap3.setTitle(R.string.preference_title_remap_tap3);
    	mPrefOnTap3.setEntries(mRemapOnNames);
    	mPrefOnTap3.setEntryValues(mRemapOnValues);
    	mPrefOnTap3.setValue(Common.Remap.getKeyTap(this, mKeyCurrent, Common.Remap.SCREEN_ON, 2));
    	mPrefOnTap3.setOnPreferenceChangeListener(this);
    	if (isUnlocked) {
    		mCategAwake.addPreference(mPrefOnTap3);
    	}
    	
    	mPrefOnPress1 = new ListPreference(this);
    	mPrefOnPress1.setKey(Common.Remap.KEY_ON_ACTION_PRESS1 + mKeyCurrent);
    	mPrefOnPress1.setPersistent(false);
    	mPrefOnPress1.setTitle(R.string.preference_title_remap_press1);
    	mPrefOnPress1.setEntries(mRemapOnNames);
    	mPrefOnPress1.setEntryValues(mRemapOnValues);
    	mPrefOnPress1.setValue(Common.Remap.getKeyPress(this, mKeyCurrent, Common.Remap.SCREEN_ON, 0));
    	mPrefOnPress1.setOnPreferenceChangeListener(this);
    	mCategAwake.addPreference(mPrefOnPress1);
    	
    	mPrefOnPress2 = new ListPreference(this);
    	mPrefOnPress2.setKey(Common.Remap.KEY_ON_ACTION_PRESS2 + mKeyCurrent);
    	mPrefOnPress2.setPersistent(false);
    	mPrefOnPress2.setTitle(R.string.preference_title_remap_press2);
    	mPrefOnPress2.setEntries(mRemapOnNames);
    	mPrefOnPress2.setEntryValues(mRemapOnValues);
    	mPrefOnPress2.setValue(Common.Remap.getKeyPress(this, mKeyCurrent, Common.Remap.SCREEN_ON, 1));
    	mPrefOnPress2.setOnPreferenceChangeListener(this);
    	if (isUnlocked) {
    		mCategAwake.addPreference(mPrefOnPress2);
    	}    	
    	
    	mPrefOnPress3 = new ListPreference(this);
    	mPrefOnPress3.setKey(Common.Remap.KEY_ON_ACTION_PRESS3 + mKeyCurrent);
    	mPrefOnPress3.setPersistent(false);
    	mPrefOnPress3.setTitle(R.string.preference_title_remap_press3);
    	mPrefOnPress3.setEntries(mRemapOnNames);
    	mPrefOnPress3.setEntryValues(mRemapOnValues);
    	mPrefOnPress3.setValue(Common.Remap.getKeyPress(this, mKeyCurrent, Common.Remap.SCREEN_ON, 2));
    	mPrefOnPress3.setOnPreferenceChangeListener(this);
    	if (isUnlocked) {
    		mCategAwake.addPreference(mPrefOnPress3);
    	}    	
    	
    	mPrefOffEnaled = new CheckBoxPreference(this);
    	mPrefOffEnaled.setKey(Common.Remap.KEY_OFF_ENABLED + mKeyCurrent);
    	mPrefOffEnaled.setPersistent(false);
    	mPrefOffEnaled.setTitle(R.string.preference_title_remap_enabled);
    	if (mKeyPrimary > 0 && !Common.Remap.isKeyEnabled(mKeyPrimary, true)) {
    		mPrefOffEnaled.setChecked(false);
    		mPrefOffEnaled.setEnabled(false);
    	
    	} else {
    		mPrefOffEnaled.setChecked(Common.Remap.isKeyEnabled(mKeyCurrent, true));
    	}
    	mCategSleep.addPreference(mPrefOffEnaled);
    	
    	mPrefOffTap1 = new ListPreference(this);
    	mPrefOffTap1.setKey(Common.Remap.KEY_OFF_ACTION_TAP1 + mKeyCurrent);
    	mPrefOffTap1.setPersistent(false);
    	mPrefOffTap1.setTitle(R.string.preference_title_remap_tap1);
    	mPrefOffTap1.setEntries(mRemapOffNames);
    	mPrefOffTap1.setEntryValues(mRemapOffValues);
    	mPrefOffTap1.setValue(Common.Remap.getKeyTap(this, mKeyCurrent, Common.Remap.SCREEN_OFF, 0));
    	mPrefOffTap1.setOnPreferenceChangeListener(this);
    	mCategSleep.addPreference(mPrefOffTap1);
    	
    	mPrefOffTap2 = new ListPreference(this);
    	mPrefOffTap2.setKey(Common.Remap.KEY_OFF_ACTION_TAP2 + mKeyCurrent);
    	mPrefOffTap2.setPersistent(false);
    	mPrefOffTap2.setTitle(R.string.preference_title_remap_tap2);
    	mPrefOffTap2.setEntries(mRemapOffNames);
    	mPrefOffTap2.setEntryValues(mRemapOffValues);
    	mPrefOffTap2.setValue(Common.Remap.getKeyTap(this, mKeyCurrent, Common.Remap.SCREEN_OFF, 1));
    	mPrefOffTap2.setOnPreferenceChangeListener(this);
    	if (isUnlocked) {
    		mCategSleep.addPreference(mPrefOffTap2);
    	}
    	
    	mPrefOffTap3 = new ListPreference(this);
    	mPrefOffTap3.setKey(Common.Remap.KEY_OFF_ACTION_TAP3 + mKeyCurrent);
    	mPrefOffTap3.setPersistent(false);
    	mPrefOffTap3.setTitle(R.string.preference_title_remap_tap3);
    	mPrefOffTap3.setEntries(mRemapOffNames);
    	mPrefOffTap3.setEntryValues(mRemapOffValues);
    	mPrefOffTap3.setValue(Common.Remap.getKeyTap(this, mKeyCurrent, Common.Remap.SCREEN_OFF, 2));
    	mPrefOffTap3.setOnPreferenceChangeListener(this);
    	if (isUnlocked) {
    		mCategSleep.addPreference(mPrefOffTap3);
    	}
    	
    	mPrefOffPress1 = new ListPreference(this);
    	mPrefOffPress1.setKey(Common.Remap.KEY_OFF_ACTION_PRESS1 + mKeyCurrent);
    	mPrefOffPress1.setPersistent(false);
    	mPrefOffPress1.setTitle(R.string.preference_title_remap_press1);
    	mPrefOffPress1.setEntries(mRemapOffNames);
    	mPrefOffPress1.setEntryValues(mRemapOffValues);
    	mPrefOffPress1.setValue(Common.Remap.getKeyPress(this, mKeyCurrent, Common.Remap.SCREEN_OFF, 0));
    	mPrefOffPress1.setOnPreferenceChangeListener(this);
    	mCategSleep.addPreference(mPrefOffPress1);
    	
    	mPrefOffPress2 = new ListPreference(this);
    	mPrefOffPress2.setKey(Common.Remap.KEY_OFF_ACTION_PRESS2 + mKeyCurrent);
    	mPrefOffPress2.setPersistent(false);
    	mPrefOffPress2.setTitle(R.string.preference_title_remap_press2);
    	mPrefOffPress2.setEntries(mRemapOffNames);
    	mPrefOffPress2.setEntryValues(mRemapOffValues);
    	mPrefOffPress2.setValue(Common.Remap.getKeyPress(this, mKeyCurrent, Common.Remap.SCREEN_OFF, 1));
    	mPrefOffPress2.setOnPreferenceChangeListener(this);
    	if (isUnlocked) {
    		mCategSleep.addPreference(mPrefOffPress2);
    	}
    	
    	mPrefOffPress3 = new ListPreference(this);
    	mPrefOffPress3.setKey(Common.Remap.KEY_OFF_ACTION_PRESS3 + mKeyCurrent);
    	mPrefOffPress3.setPersistent(false);
    	mPrefOffPress3.setTitle(R.string.preference_title_remap_press3);
    	mPrefOffPress3.setEntries(mRemapOffNames);
    	mPrefOffPress3.setEntryValues(mRemapOffValues);
    	mPrefOffPress3.setValue(Common.Remap.getKeyPress(this, mKeyCurrent, Common.Remap.SCREEN_OFF, 2));
    	mPrefOffPress3.setOnPreferenceChangeListener(this);
    	if (isUnlocked) {
    		mCategSleep.addPreference(mPrefOffPress3);
    	}
    	
    	Common.updateListSummary(mPrefOptions, mOptionsListValues, mOptionsListNames);
    	Common.updateListSummary(mPrefOnTap1, mRemapOnValues, mRemapOnNames);
    	Common.updateListSummary(mPrefOnTap2, mRemapOnValues, mRemapOnNames);
    	Common.updateListSummary(mPrefOnTap3, mRemapOnValues, mRemapOnNames);
    	Common.updateListSummary(mPrefOnPress1, mRemapOnValues, mRemapOnNames);
   	    Common.updateListSummary(mPrefOnPress2, mRemapOnValues, mRemapOnNames);
    	Common.updateListSummary(mPrefOnPress3, mRemapOnValues, mRemapOnNames);
    	Common.updateListSummary(mPrefOffTap1, mRemapOffValues, mRemapOffNames);
    	Common.updateListSummary(mPrefOffTap2, mRemapOffValues, mRemapOffNames);
    	Common.updateListSummary(mPrefOffTap3, mRemapOnValues, mRemapOnNames);
    	Common.updateListSummary(mPrefOffPress1, mRemapOnValues, mRemapOnNames);
    	Common.updateListSummary(mPrefOffPress2, mRemapOnValues, mRemapOnNames);
    	Common.updateListSummary(mPrefOffPress3, mRemapOffValues, mRemapOffNames);
    	
    	handleEnabledState(mPrefOnEnaled);
    	handleEnabledState(mPrefOffEnaled);
	}
	
	@Override
	protected void onResume() {
		super.onResume();
		
		if (mDialog != null && mDialog.getDialog() != null && mDialog.getDialog().isShowing()) {
			requestInterceptStart();
		}
	}
	
	@Override
	protected void onPause() {
		super.onPause();
		
		if (mDialog != null && mDialog.getDialog() != null && mDialog.getDialog().isShowing()) {
			requestInterceptStop();
		}

		if (mUpdated) {
			Common.requestConfigUpdate(this);
			mUpdated = false;
		}
	}
	
	@Override
	protected void onStop() {
		super.onStop();
		
		if (mDialog != null) {
			mDialog.destroy();
			mDialog = null;
		}
	}

	@Override
	public boolean onPreferenceClick(Preference preference) {
		if (preference == mPrefRemoveButton) {
			setResult(mKeyCurrent);
			
			finish();
			
		} else if (preference == mPrefAddButton) {
			mDialog = new DialogBroadcastReceiver(this, R.layout.dialog_intercept_key);
			mDialog.setTitle(R.string.alert_dialog_title_intercept_key);
			mDialog.setBroadcastIntent(new IntentFilter(Common.BroadcastOptions.INTENT_ACTION_RESPONSE));
			mDialog.setOnDialogListener(new OnDialogListener(){
				
				private Integer mKeySecondaryCode;
				private String mKeyText;
				
				@Override
				public void OnClose(DialogBroadcastReceiver dialog, Boolean positive) {
					requestInterceptStop();
					
					dialog.destroy();
					dialog = null;
					
					if (positive) {
						if (mKeySecondaryCode != null && mKeySecondaryCode > 0) {
							int keyCode = Common.generateKeyCode(mKeyCurrent, mKeySecondaryCode);
							SharedPreferences sharedPreferences = Common.getSharedPreferences(ActivityRemapButton.this);
							String[] keyArray = sharedPreferences.getString(Common.Remap.KEY_COLLECTION_SECONDARY+mKeyCurrent, "").split(",");
							List<String> keyList = new ArrayList<String>();
							
							for (int i=0; i < keyArray.length; i++) {
								if (keyArray[i] != null && keyArray[i].matches("^[0-9]+$")) {
									keyList.add(keyArray[i]);
								}
							}
							
							if (!keyList.contains(keyCode)) {
								keyList.add("" + keyCode);
								
								sharedPreferences.edit().putString(Common.Remap.KEY_COLLECTION_SECONDARY+mKeyCurrent, TextUtils.join(",", keyList)).apply();
								
								createKeyPreference(keyCode);
								
							} else {
								Toast.makeText(ActivityRemapButton.this, R.string.alert_dialog_title_key_exists, Toast.LENGTH_LONG).show();
							}
						}
					}
				}
	
				@Override
				public void OnOpen(DialogBroadcastReceiver dialog) {
					if (mDialog == null) {
						mDialog = dialog;
					}
					
					mKeyText = mKeyCurrent + " (" + Common.keycodeToString(mKeyCurrent) + ")";
					
					((TextView) mDialog.getDialog().findViewById(R.id.content_name)).setText("Key Code");
					((TextView) mDialog.getDialog().findViewById(R.id.content_text)).setText("Press the key that you would like to add");
					
					dialog.bind();
					
					requestInterceptStart();
				}
	
				@Override
				public void OnReceive(DialogBroadcastReceiver dialog, Intent intent) {
					if (intent.hasExtra("response")) {
						int key = intent.getIntExtra("response", 0);
						
						if (key != 0 && key != mKeyCurrent) {
							mKeySecondaryCode = key;
									
							((TextView) mDialog.getDialog().findViewById(R.id.content_value)).setText("" + mKeyText + " + " + mKeySecondaryCode + " (" + Common.keycodeToString(mKeySecondaryCode) + ")");
						}
					}
				}
			});
			
			mDialog.open();
		
			return true;
			
		} else if (preference.getIntent() != null) {
			startActivityForResult(preference.getIntent(), preference.getIntent().getIntExtra("keycode", 0));
			
			return true;
		}
		
		return false;
	}

	@Override
	public boolean onPreferenceChange(Preference preference, Object value) {
		if (preference == mPrefOptions) {
			PreferenceScreen screen = getPreferenceScreen();
			
			if (((String) value).equals("sleep")) {
				screen.removePreference(mCategAwake);
				screen.addPreference(mCategSleep);
				
				mPrefOptions.setValue("sleep");
				
				if (mKeyPrimary > 0 && !Common.Remap.isKeyEnabled(mKeyPrimary, true)) {
					Toast.makeText(this, R.string.message_primary_disabled_sleep, Toast.LENGTH_SHORT).show();
				}
				
			} else {
				screen.removePreference(mCategSleep);
				screen.addPreference(mCategAwake);
				
				mPrefOptions.setValue("awake");
				
				if (mKeyPrimary > 0 && !Common.Remap.isKeyEnabled(mKeyPrimary, false)) {
					Toast.makeText(this, R.string.message_primary_disabled_awake, Toast.LENGTH_SHORT).show();
				}
			}
			
			Common.updateListSummary(mPrefOptions, mOptionsListValues, mOptionsListNames);
			
			return true;
			
		} else if (preference == mPrefOnTap1 || 
				preference == mPrefOnTap2 ||
				preference == mPrefOnTap3 ||
				preference == mPrefOnPress1 || 
				preference == mPrefOnPress2 || 
				preference == mPrefOnPress3 || 
				preference == mPrefOffTap1 || 
				preference == mPrefOffTap2 || 
				preference == mPrefOffTap3 || 
				preference == mPrefOffPress1 ||
				preference == mPrefOffPress2 ||
				preference == mPrefOffPress3) {
			
			/*
			String key = preference.getKey();
			String val = (String) value;
			if (preference == mPrefOnClick ||
			 
					preference == mPrefOnTap ||
					preference == mPrefOnPress) {
				//TODO
				/*
				if (val.startsWith("keycode:")) {
					AlertDialog.Builder dialog = new AlertDialog.Builder(this);
					
					, R.layout.custom_app_action);
					dialog.setTitle(val);
					dialog.setOnDialogListener(new OnDialogListener(){
						
						@Override
						public void OnClose(DialogBroadcastReceiver dialog, Boolean positive) {
							requestInterceptStop();
							
							dialog.destroy();
							dialog = null;
							
							if (positive) {
								if (mKeySecondaryCode != null && mKeySecondaryCode > 0) {
									int keyCode = Common.generateKeyCode(mKeyCurrent, mKeySecondaryCode);
									SharedPreferences sharedPreferences = Common.getSharedPreferences(ActivityRemapButton.this);
									String[] keyArray = sharedPreferences.getString(Common.Remap.KEY_COLLECTION_SECONDARY+mKeyCurrent, "").split(",");
									List<String> keyList = new ArrayList<String>();
									
									for (int i=0; i < keyArray.length; i++) {
										if (keyArray[i] != null && keyArray[i].matches("^[0-9]+$")) {
											keyList.add(keyArray[i]);
										}
									}
									
									if (!keyList.contains(keyCode)) {
										keyList.add("" + keyCode);
										
										sharedPreferences.edit().putString(Common.Remap.KEY_COLLECTION_SECONDARY+mKeyCurrent, TextUtils.join(",", keyList)).apply();
										
										createKeyPreference(keyCode);
										
									} else {
										Toast.makeText(ActivityRemapButton.this, R.string.alert_dialog_title_key_exists, Toast.LENGTH_LONG).show();
									}
								}
							}
						}
			
						@Override
						public void OnOpen(DialogBroadcastReceiver dialog) {
							if (mDialog == null) {
								mDialog = dialog;
							}
						}
					});
					
					mDialog.open();
				
					return true;
				}
			}
			*/
			Common.getSharedPreferences(this).edit().putString(preference.getKey(), (String) value).apply();
			
			((ListPreference) preference).setValue((String) value);
			
			if (preference == mPrefOnTap1 || 
					preference == mPrefOnTap2 || 
					preference == mPrefOnTap3 ||
					preference == mPrefOnPress1 ||
					preference == mPrefOnPress2 ||
					preference == mPrefOnPress3) {

				Common.updateListSummary((ListPreference) preference, mRemapOnValues, mRemapOnNames);

			} else if (preference == mPrefOffTap1 || 
					preference == mPrefOffTap2 || 
					preference == mPrefOffTap3 ||
					preference == mPrefOffPress1 ||
					preference == mPrefOffPress2 ||
					preference == mPrefOffPress3) {

				Common.updateListSummary((ListPreference) preference, mRemapOffValues, mRemapOffNames);
			}
			
			mUpdated = true;
			
			return true;
		}
		
		return false;
	}
	
	@Override
	public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
		if (preference == mPrefOnEnaled  ||
		    preference == mPrefOffEnaled) {
			
			Common.getSharedPreferences(this).edit().putBoolean(preference.getKey(), ((CheckBoxPreference) preference).isChecked()).apply();
			
			handleEnabledState(preference);
			
			mUpdated = true;
			
			return true;
		}
		
		return false;
	}
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (resultCode > 0) {
			String keyCode = "" + Common.generateKeyCode(resultCode, 0);;
			SharedPreferences sharedPreferences = Common.getSharedPreferences(ActivityRemapButton.this);
			String[] keyArray = sharedPreferences.getString(Common.Remap.KEY_COLLECTION_SECONDARY + mKeyCurrent, "").split(",");
			List<String> keyList = new ArrayList<String>();
			
			for (int i=0; i < keyArray.length; i++) {
				if (!"".equals(keyArray[i])) {
					keyList.add(keyArray[i]);
				}
			}
			
			if (keyList.contains(keyCode)) {
				keyList.remove(keyCode);
				
				Editor editor = sharedPreferences.edit();
				
				editor.putString(Common.Remap.KEY_COLLECTION_SECONDARY + mKeyCurrent, TextUtils.join(",", keyList));
				editor.remove(Common.Remap.KEY_COLLECTION_SECONDARY + keyCode);
				editor.remove(Common.Remap.KEY_OFF_ENABLED + keyCode);
				editor.remove(Common.Remap.KEY_OFF_ACTION_TAP1 + keyCode);
				editor.remove(Common.Remap.KEY_OFF_ACTION_TAP2 + keyCode);
				editor.remove(Common.Remap.KEY_OFF_ACTION_TAP3 + keyCode);
				editor.remove(Common.Remap.KEY_OFF_ACTION_PRESS1 + keyCode);
				editor.remove(Common.Remap.KEY_OFF_ACTION_PRESS2 + keyCode);
				editor.remove(Common.Remap.KEY_OFF_ACTION_PRESS3 + keyCode);
				editor.remove(Common.Remap.KEY_ON_ENABLED + keyCode);
				editor.remove(Common.Remap.KEY_ON_ACTION_TAP1 + keyCode);
				editor.remove(Common.Remap.KEY_ON_ACTION_TAP2 + keyCode);
				editor.remove(Common.Remap.KEY_ON_ACTION_TAP3 + keyCode);
				editor.remove(Common.Remap.KEY_ON_ACTION_PRESS1 + keyCode);
				editor.remove(Common.Remap.KEY_ON_ACTION_PRESS2 + keyCode);
				editor.remove(Common.Remap.KEY_ON_ACTION_PRESS3 + keyCode);
				
				editor.apply();
				
				mUpdated = true;
				
				mPrefButtons.removePreference( findPreference("keycode/" + keyCode) );
				
		    	if (mPrefButtons.getPreferenceCount() == 0) {
		    		getPreferenceScreen().removePreference(mPrefButtons);
		    	}
				
				Toast.makeText(this, R.string.key_removed, Toast.LENGTH_LONG).show();
			}
		}
	}
	
	private void handleEnabledState(Preference preference) {
		if (preference == mPrefOnEnaled) {
			Boolean isEnabled = mPrefOnEnaled.isChecked();
			mPrefOnTap1.setEnabled( isEnabled );
			mPrefOnTap2.setEnabled( isEnabled );
			mPrefOnTap3.setEnabled( isEnabled );
			mPrefOnPress1.setEnabled( isEnabled );
			mPrefOnPress2.setEnabled( isEnabled );
			mPrefOnPress3.setEnabled( isEnabled );
			
		} else if (preference == mPrefOffEnaled) {
			Boolean isEnabled = mPrefOffEnaled.isChecked();
			mPrefOffTap1.setEnabled( isEnabled );
			mPrefOffTap2.setEnabled( isEnabled );
			mPrefOffTap3.setEnabled( isEnabled );
			mPrefOffPress1.setEnabled( isEnabled );
			mPrefOffPress2.setEnabled( isEnabled );
			mPrefOffPress3.setEnabled( isEnabled );
		}
	}
	
	protected void createKeyPreference(Integer keyCode) {
    	Intent intent = new Intent();
    	intent.setAction( Intent.ACTION_VIEW );
    	intent.setClass(this, this.getClass());
    	intent.putExtra("keycode", (int) keyCode);
    	intent.putExtra("keyprimary", (int) mKeyCurrent);
    	
    	int keySecondary = Common.extractKeyCode(mKeyCurrent, keyCode);
    	
    	Preference buttonPreference = new Preference(this);
    	buttonPreference.setKey("keycode/" + keyCode);
    	buttonPreference.setTitle(Common.keycodeToString(mKeyCurrent) + " + " + Common.keycodeToString(keySecondary));
    	buttonPreference.setSummary("Key Code " + mKeyCurrent + "+" + keySecondary);
    	buttonPreference.setIntent(intent);
    	buttonPreference.setOnPreferenceClickListener(this);
    	
    	if (mPrefButtons.getPreferenceCount() == 0) {
    		getPreferenceScreen().addPreference(mPrefButtons);
    	}

    	mPrefButtons.addPreference(buttonPreference);
	}
	
	protected void requestInterceptStart() {
		Intent intent = new Intent(Common.BroadcastOptions.INTENT_ACTION_REQUEST);
		intent.putExtra("request", Common.BroadcastOptions.REQUEST_ENABLE_KEYCODE_INTERCEPT);
		
		sendBroadcast(intent);
	}
	
	protected void requestInterceptStop() {
		Intent intent = new Intent(Common.BroadcastOptions.INTENT_ACTION_REQUEST);
		intent.putExtra("request", Common.BroadcastOptions.REQUEST_DISABLE_KEYCODE_INTERCEPT);
		
		sendBroadcast(intent);
	}
}
