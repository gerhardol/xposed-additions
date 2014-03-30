package com.spazedog.xposed.additionsgb;

import java.util.ArrayList;

import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.view.View;
import android.widget.CheckBox;

import com.spazedog.xposed.additionsgb.Common.Index;
import com.spazedog.xposed.additionsgb.backend.service.XServiceManager;
import com.spazedog.xposed.additionsgb.tools.views.IWidgetPreference;
import com.spazedog.xposed.additionsgb.tools.views.WidgetPreference;
import com.spazedog.xposed.additionsgb.tools.views.IWidgetPreference.OnWidgetBindListener;
import com.spazedog.xposed.additionsgb.tools.views.IWidgetPreference.OnWidgetClickListener;

public class ActivityScreenRemapCondition extends PreferenceActivity implements OnPreferenceClickListener, OnWidgetClickListener, OnWidgetBindListener {
	
	private XServiceManager mPreferences;
	
	private Boolean mSetup = false;
	
	private String mKey;
	private String mCondition;
	
	private ArrayList<String> mKeyActions = new ArrayList<String>();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		addPreferencesFromResource(R.xml.activity_screen_remap_condition);
		
		mKey = getIntent().getStringExtra("key");
		mCondition = getIntent().getStringExtra("condition");
		
		if (mKey == null || mCondition == null) {
			finish();
			
		} else {
			setTitle( Common.conditionToString(this, mCondition) );
		}
	}

    @Override
    protected void onStart() {
    	super.onStart();
    	
    	mPreferences = XServiceManager.getInstance();
    }
    
    @Override
    protected void onResume() {
    	super.onResume();
    	
    	if (mPreferences == null) {
    		finish();
    		
    	} else {
    		setup();
    	}
    }
    
    @Override
    protected void onStop() {
    	super.onStop();
    	
    	if (mPreferences != null)
    		mPreferences.commit();
    	
    	mPreferences = null;
    }
    
    private void setup() {
    	if (mSetup != (mSetup = true)) {
			mKeyActions = (ArrayList<String>) mPreferences.getStringArrayGroup(String.format(Index.array.groupKey.remapKeyActions_$, mCondition), mKey, new ArrayList<String>());
			
			String condition = Common.getConditionIdentifier(this, mCondition) > 0 ? mCondition : "on";
			
			while (mKeyActions.size() < 2*3) {
				mKeyActions.add(null);
			}
						
			WidgetPreference pressPreference = (WidgetPreference) findPreference("state_press_preference");
			pressPreference.setSummary( mKeyActions.get(0) != null ? Common.actionToString(this, mKeyActions.get(0)) : "" );
			pressPreference.setOnWidgetBindListener(this);
			pressPreference.setOnWidgetClickListener(this);
			pressPreference.setOnPreferenceClickListener(this);
			pressPreference.setIntent( 
					new Intent(this, ActivitySelectorRemap.class)
					.putExtra("action", "add_action")
					.putExtra("index", 0)
					.putExtra("condition", condition)
			);

			WidgetPreference clickPreference = (WidgetPreference) findPreference("state_click_preference");
			clickPreference.setSummary( mKeyActions.get(1) != null ? Common.actionToString(this, mKeyActions.get(1)) : "" );
			clickPreference.setOnWidgetBindListener(this);
			clickPreference.setOnWidgetClickListener(this);
			clickPreference.setOnPreferenceClickListener(this);
			clickPreference.setIntent( 
					new Intent(this, ActivitySelectorRemap.class)
					.putExtra("action", "add_action")
					.putExtra("index", 1)
					.putExtra("condition", condition)
			);
			
			if (mPreferences.isPackageUnlocked()) {
    			WidgetPreference press2Preference = (WidgetPreference) findPreference("state_press2_preference");
    			press2Preference.setSummary( mKeyActions.get(2) != null ? Common.actionToString(this, mKeyActions.get(2)) : "" );
    			press2Preference.setOnWidgetBindListener(this);
    			press2Preference.setOnWidgetClickListener(this);
    			press2Preference.setOnPreferenceClickListener(this);
    			press2Preference.setIntent( 
    					new Intent(this, ActivitySelectorRemap.class)
    					.putExtra("action", "add_action")
    					.putExtra("index", 2)
    					.putExtra("condition", condition)
    			);
    			
    			WidgetPreference tapPreference = (WidgetPreference) findPreference("state_tap_preference");
    			tapPreference.setSummary( mKeyActions.get(3) != null ? Common.actionToString(this, mKeyActions.get(3)) : "" );
    			tapPreference.setOnWidgetBindListener(this);
    			tapPreference.setOnWidgetClickListener(this);
    			tapPreference.setOnPreferenceClickListener(this);
    			tapPreference.setIntent( 
    					new Intent(this, ActivitySelectorRemap.class)
    					.putExtra("action", "add_action")
    					.putExtra("index", 3)
    					.putExtra("condition", condition)
    			);
    			
    			WidgetPreference press3Preference = (WidgetPreference) findPreference("state_press3_preference");
    			press3Preference.setSummary( mKeyActions.get(4) != null ? Common.actionToString(this, mKeyActions.get(4)) : "" );
    			press3Preference.setOnWidgetBindListener(this);
    			press3Preference.setOnWidgetClickListener(this);
    			press3Preference.setOnPreferenceClickListener(this);
    			press3Preference.setIntent( 
    					new Intent(this, ActivitySelectorRemap.class)
    					.putExtra("action", "add_action")
    					.putExtra("index", 4)
    					.putExtra("condition", condition)
    			);
    			WidgetPreference tap3Preference = (WidgetPreference) findPreference("state_tap3_preference");
    			tap3Preference.setSummary( mKeyActions.get(5) != null ? Common.actionToString(this, mKeyActions.get(5)) : "" );
    			tap3Preference.setOnWidgetBindListener(this);
    			tap3Preference.setOnWidgetClickListener(this);
    			tap3Preference.setOnPreferenceClickListener(this);
    			tap3Preference.setIntent( 
    					new Intent(this, ActivitySelectorRemap.class)
    					.putExtra("action", "add_action")
    					.putExtra("index", 5)
    					.putExtra("condition", condition)
    			);
    			
			} else {
				((PreferenceCategory) findPreference("actions_group")).removePreference(findPreference("state_press2_preference"));
				((PreferenceCategory) findPreference("actions_group")).removePreference(findPreference("state_tap_preference"));
				((PreferenceCategory) findPreference("actions_group")).removePreference(findPreference("state_press3_preference"));
				((PreferenceCategory) findPreference("actions_group")).removePreference(findPreference("state_tap3_preference"));
			}
    	}
    }
    
	@Override
	public boolean onPreferenceClick(Preference preference) {
		if (preference.getIntent() != null) {
			startActivityForResult(preference.getIntent(), 2); return true;
		}
		
		return false;
	}
	
	private Integer actionToIndex(String keyName) {
		int i = 0;
		for (; i < 5; i++) {
			if (keyName.equals(indexToAction[i])) {
				break;
			}
		}
		return i;
	}
	private final String indexToAction[] = {"state_press_preference", "state_click_preference", "state_press2_preference", "state_tap_preference", "state_press3_preference", "state_tap3_preference"};
	
	@Override
	public void onWidgetClick(Preference preference, View widgetView) {
		if (preference.getKey().startsWith("state_")) {
			String keyName = preference.getKey();
			Integer index = actionToIndex(keyName);
			
			CheckBox checbox = (CheckBox) widgetView.findViewById(android.R.id.checkbox);
			checbox.setChecked( !checbox.isChecked() );
			
			((IWidgetPreference) preference).setPreferenceEnabled(checbox.isChecked());

			mKeyActions.set(index, checbox.isChecked() ? "disabled" : null);
			mPreferences.putStringArrayGroup(String.format(Index.array.groupKey.remapKeyActions_$, mCondition), mKey, mKeyActions, true);

			preference.setSummary( mKeyActions.get(index) != null ? Common.actionToString(this, mKeyActions.get(index)) : "" );
		}
	}
	
	@Override
	public void onWidgetBind(Preference preference, View widgetView) {
		String keyName = preference.getKey();
		Integer index = actionToIndex(keyName);
		
		CheckBox checbox = (CheckBox) widgetView.findViewById(android.R.id.checkbox);
		checbox.setChecked( mKeyActions.get(index) != null );
		
		((IWidgetPreference) preference).setPreferenceEnabled(checbox.isChecked());
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		/*
		 * onActivityResult is called before anything else, 
		 * so this has not yet been re-initiated after onStop
		 */
		mPreferences = XServiceManager.getInstance();
		
		if (resultCode == RESULT_OK && "add_action".equals(data.getStringExtra("action"))) {
			String action = data.getStringExtra("result");
			Integer index = data.getIntExtra("index", 0);
			String keyName = indexToAction[index];
			
			mKeyActions.set(index, action);
			mPreferences.putStringArrayGroup(String.format(Index.array.groupKey.remapKeyActions_$, mCondition), mKey, mKeyActions, true);
			
			findPreference(keyName).setSummary( mKeyActions.get(index) != null ? Common.actionToString(this, mKeyActions.get(index)) : "" );
		}
	}
}
