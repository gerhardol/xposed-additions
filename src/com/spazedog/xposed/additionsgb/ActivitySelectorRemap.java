/*
 * This file is part of the Xposed Additions Project: https://github.com/spazedog/xposed-additions
 *  
 * Copyright (c) 2014 Daniel Bergløv
 *
 * Xposed Additions is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * Xposed Additions is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with Xposed Additions. If not, see <http://www.gnu.org/licenses/>
 */

package com.spazedog.xposed.additionsgb;

import net.dinglisch.android.tasker.TaskerIntent;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.widget.ListView;

import com.spazedog.xposed.additionsgb.Common.AppBuilder;
import com.spazedog.xposed.additionsgb.Common.AppBuilder.BuildAppView;
import com.spazedog.xposed.additionsgb.Common.RemapAction;
import com.spazedog.xposed.additionsgb.backend.service.XServiceManager;
import com.spazedog.xposed.additionsgb.configs.Actions;
import com.spazedog.xposed.additionsgb.tools.views.IWidgetPreference;
import com.spazedog.xposed.additionsgb.tools.views.WidgetPreference;

public class ActivitySelectorRemap extends PreferenceActivity implements OnPreferenceClickListener {
	
	private XServiceManager mPreferences;
	
	private Boolean mSetup = false;
	
	private String mAction;
	
	private AppBuilder mAppBuilder;

    final int REQUEST_SELECT_TASKER = 1;
    final int REQUEST_SELECT_APPSHORTCUT = 2;
    final int REQUEST_CREATE_APPSHORTCUT = 3;

    private Intent getAppShortcutSelectIntent() {
        Intent intent = new Intent(Intent.ACTION_PICK_ACTIVITY);

        intent.putExtra(Intent.EXTRA_INTENT, new Intent(Intent.ACTION_CREATE_SHORTCUT));
        intent.putExtra(Intent.EXTRA_TITLE, R.string.preference_title_select_appshortcut);
        return intent;
    }


    @Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		addPreferencesFromResource(R.xml.activity_selector_remap);
		
		mAction = getIntent().getStringExtra("action");
		mAppBuilder = new AppBuilder( getListView() );
		
		if ("add_condition".equals(mAction)) {
			setTitle(R.string.preference_add_condition);
			
		} else if ("add_action".equals(mAction)) {
			setTitle(R.string.preference_add_action);
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
    		onBackPressed();
    		
    	} else {
    		setup();
    	}
    }
    
    @Override
    protected void onStop() {
    	super.onStop();
    	
    	mPreferences = null;
    }
    
    @Override
    public void onBackPressed() {
    	setResult(RESULT_CANCELED);
    	
    	finish();
    }
    
    @Override
    protected void onDestroy() {
    	super.onDestroy();
    	
    	mAppBuilder.destroy();
    }
    
    private void setup() {
    	if (mSetup != (mSetup = true)) {
    		PreferenceScreen preferenceScreen = getPreferenceScreen();
    		
    		if ("add_condition".equals(mAction)) {
				preferenceScreen.removePreference(findPreference("custom_group"));
				preferenceScreen.addPreference(getSelectPreference(getResources().getString(R.string.condition_type_$on), null, "on", null, null));
				preferenceScreen.addPreference(getSelectPreference(getResources().getString(R.string.condition_type_$off), null, "off", null, null));
				preferenceScreen.addPreference(getSelectPreference(getResources().getString(R.string.condition_type_$guard), null, "guard", null, null));
				
    		} else if ("add_action".equals(mAction)) {
    			PreferenceCategory category = (PreferenceCategory) findPreference("custom_group");
    			String condition = getIntent().getStringExtra("condition");
    			
    			for (RemapAction current : Actions.COLLECTION) {
    				Boolean isValid = current.isValid(this, condition);
    				Boolean displayAlert = current.hasAlert(this);
    				String alertMsg = current.getAlert(this);
    				String noticeMsg = current.getNotice(this);
    				
    				if (isValid || displayAlert) {
    					PreferenceGroup preferenceGroup = null;
    					Preference preference = null;
    					
    					if (current.isDispatchAction()) {
    						preferenceGroup = preferenceScreen;
    						preference = getSelectPreference(current.getLabel(this), getResources().getString(R.string.text_key, current.getAction()), current.getAction(), null, null);
    						
    					} else {
    						preferenceGroup = category;
    						preference = getSelectPreference(current.getLabel(this), current.getDescription(this), current.getAction(), null, null);
    					}
    					
    					if (!isValid && displayAlert) {
    						setDialog(null, alertMsg, preference, false);
    						
    					} else if (noticeMsg != null) {
    						setDialog(null, noticeMsg, preference, true);
    					}
    					
    					preferenceGroup.addPreference(preference);
    				}
    			}
    		}

            if (mPreferences.isPackageUnlocked()) {
                if ("add_action".equals(mAction)) {
                    findPreference("select_appshortcut_preference").setOnPreferenceClickListener(this);
                } else {
                    ((PreferenceGroup) findPreference("application_group")).removePreference(findPreference("select_appshortcut_preference"));
                }

                if ("add_action".equals(mAction) && TaskerIntent.testStatus(this).equals(TaskerIntent.Status.OK)) {
                        findPreference("select_tasker_preference").setOnPreferenceClickListener(this);
                } else {
                    ((PreferenceGroup) findPreference("application_group")).removePreference(findPreference("select_tasker_preference"));
                }

                if ("add_action".equals(mAction) && "off".equals(getIntent().getStringExtra("condition"))) {
                    ((PreferenceGroup) findPreference("application_group")).removePreference(findPreference("load_apps_preference"));
                } else {
                    findPreference("load_apps_preference").setOnPreferenceClickListener(this);
                }

            } else {
                preferenceScreen.removePreference(findPreference("application_group"));
            }
        }
    }
	
	@Override
	public boolean onPreferenceClick(Preference preference) {
		if ("load_apps_preference".equals(preference.getKey())) {
			final PreferenceCategory category = (PreferenceCategory) findPreference("application_group");
			category.removePreference(preference);
			
			Drawable tmpIcon = new ColorDrawable(android.R.color.transparent);
			
			try {
				tmpIcon = getPackageManager().getApplicationIcon("android");
				
			} catch (NameNotFoundException e) {}
			
			final Drawable defaultIcon = tmpIcon;
			
			mAppBuilder.build(new BuildAppView(){
				@Override
				public void onBuildAppView(ListView view, String name, String label) {
					category.addPreference(
						getSelectPreference(
							label,
							name,
							name,
							android.os.Build.VERSION.SDK_INT >= 11 ? defaultIcon : null,
							null
						)
					);
				}
			});

        } else if ("select_tasker_preference".equals(preference.getKey())) {
            startActivityForResult(TaskerIntent.getTaskSelectIntent(), REQUEST_SELECT_TASKER);

        } else if ("select_appshortcut_preference".equals(preference.getKey())) {
            startActivityForResult(getAppShortcutSelectIntent(), REQUEST_SELECT_APPSHORTCUT);

        } else {
			Intent intent = getIntent();
			intent.putExtra("result", (String) ((IWidgetPreference) preference).getTag());
			
			setResult(RESULT_OK, intent);
			
			finish();
		}
		
		return false;
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent intent) {

        if (intent != null) {
            Intent returnIntent;
            switch (requestCode) {

                case REQUEST_SELECT_TASKER:
                    returnIntent = getIntent();
                    returnIntent.putExtra("result", "tasker:" + intent.getDataString());

                    setResult(RESULT_OK, returnIntent);

                    finish();
                    break;

                case REQUEST_SELECT_APPSHORTCUT:
                    startActivityForResult(intent, REQUEST_CREATE_APPSHORTCUT);

                    break;

                case REQUEST_CREATE_APPSHORTCUT:
                    String name = intent.getStringExtra(Intent.EXTRA_SHORTCUT_NAME).replace(':', '_');

                    Intent appIntent = intent.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT);
                    returnIntent = getIntent();

                    returnIntent.putExtra("result", "appshortcut:" + name + ":" + appIntent.toUri(Intent.URI_INTENT_SCHEME));

                    setResult(RESULT_OK, returnIntent);

                    finish();
                    break;
            }
        }
    }

	@TargetApi(Build.VERSION_CODES.HONEYCOMB)
	private Preference getSelectPreference(String title, String summary, String tag, Drawable icon, Intent intent) {
		WidgetPreference preference = new WidgetPreference(this);
		preference.setTitle(title);
		preference.setTag(tag);
		preference.setOnPreferenceClickListener(this);
		
		if (summary != null) {
			preference.setSummary(summary);
		}
		
		if (icon != null) {
			preference.setIcon(icon);
		}
		
		return preference;
	}
	
	private void setDialog(final String headline, final String message, Preference preference, final Boolean dispatchEvent) {
		preference.setOnPreferenceClickListener(new OnPreferenceClickListener(){
			@Override
			public boolean onPreferenceClick(final Preference preference) {
				new AlertDialog.Builder(ActivitySelectorRemap.this)
				.setTitle(headline)
				.setMessage(message)
				.setCancelable(false)
				.setNegativeButton(android.R.string.ok, new DialogInterface.OnClickListener() {
					@Override
		            public void onClick(DialogInterface dialog, int id) {
						dialog.cancel();
						
						if (dispatchEvent) {
							ActivitySelectorRemap.this.onPreferenceClick(preference);
						}
					}
				})
				.show();
				
				return false;
			}
		});
	}
}
