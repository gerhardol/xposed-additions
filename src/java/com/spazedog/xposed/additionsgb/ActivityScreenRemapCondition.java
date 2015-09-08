package com.spazedog.xposed.additionsgb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.support.v7.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import com.spazedog.xposed.additionsgb.backend.service.XServiceManager;
import com.spazedog.xposed.additionsgb.configs.Settings;
import com.spazedog.xposed.additionsgb.tools.views.IWidgetPreference;
import com.spazedog.xposed.additionsgb.tools.views.IWidgetPreference.OnWidgetBindListener;
import com.spazedog.xposed.additionsgb.tools.views.IWidgetPreference.OnWidgetClickListener;
import com.spazedog.xposed.additionsgb.tools.views.WidgetPreference;

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
		}
	}
	
	@Override
	protected void onPostCreate(Bundle savedInstanceState) {
		super.onPostCreate(savedInstanceState);
		
		if (Build.VERSION.SDK_INT >= 14) {
			LinearLayout root = (LinearLayout)findViewById(android.R.id.list).getParent().getParent().getParent();
			Toolbar bar = (Toolbar) LayoutInflater.from(this).inflate(R.layout.actionbar_v14_layout, root, false);
			bar.setTitle( Common.conditionToString(this, mCondition) );
			
			root.addView(bar, 0);
			
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
    protected void onPause() {
    	super.onPause();
    	
    	if (mPreferences != null) {
    		mPreferences.apply();
    	}
    }
    
    @Override
    protected void onStop() {
    	super.onStop();

    	mPreferences = null;
    }
    
    private void setup() {
    	if (mSetup != (mSetup = true)) {
			mKeyActions = (ArrayList<String>) mPreferences.getStringArrayGroup(Settings.REMAP_KEY_LIST_ACTIONS.get(mCondition), mKey, new ArrayList<String>());
			
			String condition = Common.getConditionIdentifier(this, mCondition) > 0 ? mCondition : "on";
			
			if (mKeyActions.size() < 6) {
				do {
					mKeyActions.add(null);
					
				} while (mKeyActions.size() < 6);
			}

            List<String> conditions  = Arrays.asList(getResources().getStringArray(R.array.default_condition_values));
            if(conditions.contains(mCondition)) {
                getPreferenceScreen().removePreference(findPreference("default_condition_preference"));

            } else {
                CheckBoxPreference defButton = (CheckBoxPreference) findPreference("default_condition_preference");
                defButton.setChecked(mPreferences.getBooleanGroup(Settings.REMAP_KEY_DEFAULT_CONDITION, mKey, false));
                defButton.setOnPreferenceClickListener(this);
            }

			
			WidgetPreference clickPreference = (WidgetPreference) findPreference("state_click_preference");
			clickPreference.setSummary( mKeyActions.get(0) != null ? Common.actionToString(this, mKeyActions.get(0)) : "" );
			clickPreference.setOnWidgetBindListener(this);
			clickPreference.setOnWidgetClickListener(this);
			clickPreference.setOnPreferenceClickListener(this);
			clickPreference.setIntent( 
					new Intent(this, ActivitySelectorRemap.class)
					.putExtra("action", "add_action")
					.putExtra("index", 0)
					.putExtra("condition", condition)
					.putExtra("preference", "state_click_preference")
			);
			
			if (mPreferences.isPackageUnlocked()) {
    			WidgetPreference tapPreference = (WidgetPreference) findPreference("state_tap_preference");
    			tapPreference.setSummary( mKeyActions.get(1) != null ? Common.actionToString(this, mKeyActions.get(1)) : "" );
    			tapPreference.setOnWidgetBindListener(this);
    			tapPreference.setOnWidgetClickListener(this);
    			tapPreference.setOnPreferenceClickListener(this);
    			tapPreference.setIntent( 
    					new Intent(this, ActivitySelectorRemap.class)
    					.putExtra("action", "add_action")
    					.putExtra("index", 1)
    					.putExtra("condition", condition)
    					.putExtra("preference", "state_tap_preference")
    			);
    			
    			WidgetPreference tripletapPreference = (WidgetPreference) findPreference("state_tripletap_preference");
    			tripletapPreference.setSummary( mKeyActions.get(4) != null ? Common.actionToString(this, mKeyActions.get(4)) : "" );
    			tripletapPreference.setOnWidgetBindListener(this);
    			tripletapPreference.setOnWidgetClickListener(this);
    			tripletapPreference.setOnPreferenceClickListener(this);
    			tripletapPreference.setIntent( 
    					new Intent(this, ActivitySelectorRemap.class)
    					.putExtra("action", "add_action")
    					.putExtra("index", 4)
    					.putExtra("condition", condition)
    					.putExtra("preference", "state_tripletap_preference")
    			);
    			
    			WidgetPreference doublepressPreference = (WidgetPreference) findPreference("state_doublepress_preference");
    			doublepressPreference.setSummary( mKeyActions.get(3) != null ? Common.actionToString(this, mKeyActions.get(3)) : "" );
    			doublepressPreference.setOnWidgetBindListener(this);
    			doublepressPreference.setOnWidgetClickListener(this);
    			doublepressPreference.setOnPreferenceClickListener(this);
    			doublepressPreference.setIntent( 
    					new Intent(this, ActivitySelectorRemap.class)
    					.putExtra("action", "add_action")
    					.putExtra("index", 3)
    					.putExtra("condition", condition)
    					.putExtra("preference", "state_doublepress_preference")
    			);
    			
    			WidgetPreference triplepressPreference = (WidgetPreference) findPreference("state_triplepress_preference");
    			triplepressPreference.setSummary( mKeyActions.get(5) != null ? Common.actionToString(this, mKeyActions.get(5)) : "" );
    			triplepressPreference.setOnWidgetBindListener(this);
    			triplepressPreference.setOnWidgetClickListener(this);
    			triplepressPreference.setOnPreferenceClickListener(this);
    			triplepressPreference.setIntent( 
    					new Intent(this, ActivitySelectorRemap.class)
    					.putExtra("action", "add_action")
    					.putExtra("index", 5)
    					.putExtra("condition", condition)
    					.putExtra("preference", "state_triplepress_preference")
    			);
    			
			} else {
				getPreferenceScreen().removePreference(findPreference("actions_double_group"));
				getPreferenceScreen().removePreference(findPreference("actions_triple_group"));
			}
			
			WidgetPreference pressPreference = (WidgetPreference) findPreference("state_press_preference");
			pressPreference.setSummary( mKeyActions.get(2) != null ? Common.actionToString(this, mKeyActions.get(2)) : "" );
			pressPreference.setOnWidgetBindListener(this);
			pressPreference.setOnWidgetClickListener(this);
			pressPreference.setOnPreferenceClickListener(this);
			pressPreference.setIntent( 
					new Intent(this, ActivitySelectorRemap.class)
					.putExtra("action", "add_action")
					.putExtra("index", 2)
					.putExtra("condition", condition)
					.putExtra("preference", "state_press_preference")
			);
    	}
    }
    
	@Override
	public boolean onPreferenceClick(Preference preference) {
        if (preference.getKey().equals("default_condition_preference")) {
            Boolean isChecked = ((CheckBoxPreference) preference).isChecked();

            mPreferences.putBooleanGroup(Settings.REMAP_KEY_DEFAULT_CONDITION, mKey, isChecked, false);

            return true;
        } else if (preference.getIntent() != null) {
			startActivityForResult(preference.getIntent(), 2);
            return true;
		}
		
		return false;
	}
	
	@Override
	public void onWidgetClick(Preference preference, View widgetView) {
		if (preference.getKey().startsWith("state_")) {
			Integer index = preference.getIntent().getExtras().getInt("index");
			
			CheckBox checbox = (CheckBox) widgetView.findViewById(android.R.id.checkbox);
			checbox.setChecked( !checbox.isChecked() );
			
			((IWidgetPreference) preference).setPreferenceEnabled(checbox.isChecked());

			mKeyActions.set(index, checbox.isChecked() ? "disabled" : null);
			mPreferences.putStringArrayGroup(Settings.REMAP_KEY_LIST_ACTIONS.get(mCondition), mKey, mKeyActions, true);

			preference.setSummary( mKeyActions.get(index) != null ? Common.actionToString(this, mKeyActions.get(index)) : "" );
		}
	}
	
	@Override
	public void onWidgetBind(Preference preference, View widgetView) {
		Integer index = preference.getIntent().getExtras().getInt("index");
		
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
			String keyName = data.getStringExtra("preference");
			
			mKeyActions.set(index, action);
			mPreferences.putStringArrayGroup(Settings.REMAP_KEY_LIST_ACTIONS.get(mCondition), mKey, mKeyActions, true);
			
			findPreference(keyName).setSummary( mKeyActions.get(index) != null ? Common.actionToString(this, mKeyActions.get(index)) : "" );
		}
	}
}
