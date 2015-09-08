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

import java.io.File;
import java.lang.ref.WeakReference;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Environment;
import android.support.v4.util.LruCache;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AbsListView;
import android.widget.AbsListView.OnScrollListener;
import android.widget.ImageView;
import android.widget.ListView;

import com.spazedog.xposed.additionsgb.backend.service.XServiceManager;
import com.spazedog.xposed.additionsgb.configs.Actions;
import com.spazedog.xposed.additionsgb.configs.Settings;


public final class Common {
	public static final Boolean DEBUG = false;
	private static Boolean ENABLE_DEBUG;
	
	public static final String PACKAGE_NAME = Common.class.getPackage().getName();
	public static final String PACKAGE_NAME_PRO = PACKAGE_NAME + ".pro";
	public static final String SERVICE_APP_PREFERENCES = "app.additionsgb.preferences.service.BIND";
	public static final String XSERVICE_NAME = "user.additionsgb.service";
	public static final String XSERVICE_NAME_COMBAT = PACKAGE_NAME + ".service.XSERVICE";
	public static final String XSERVICE_PERMISSIONS = PACKAGE_NAME + ".permissions.XSERVICE";
	
	public static final String TORCH_INTENT_ACTION = PACKAGE_NAME + ".TOGGLE_FLASHLIGHT";
	
	public static final String PREFERENCE_FILE = "config";
	
	public static class LogFile {
		public static final Long SIZE = 1024L*512;
		public static final File LOCK = new File(Environment.getDataDirectory(), "data/" + PACKAGE_NAME + "/cache/error.log.lock");
		public static final File MAIN = new File(Environment.getDataDirectory(), "data/" + PACKAGE_NAME + "/cache/error.main.log");
		public static final File STORED = new File(Environment.getDataDirectory(), "data/" + PACKAGE_NAME + "/cache/error.stored.log");
	}

    public static String[] actionParse(String action) {
        return actionParse(null, action);
    }

    //context is for this app, i.e. used in the GUI
    //context is used to get display name for the action
    private static String[] actionParse(Context context, String action) {
        //type, action, display name
        String[] result = {null, null, null};
        if (action != null) {
            if (action.matches("^[0-9]+$")) {
                result[0] = "dispatch";
                result[1] = action;
                result[2] = keyToString( Integer.parseInt(action) );

            } else if (action.startsWith("tasker:")) {
                result[0] = "tasker";
                int i = "tasker:".length();
                result[1] = action.substring(i+1);
                result[2] = result[1];
                if (context != null) {
                    result[2] = context.getResources().getString(R.string.preference_title_select_tasker) + ": "+ result[2];
                }

            } else if (action.startsWith("shortcut:")) {
                result[0] = "shortcut";
                int i = "shortcut:".length();
                int j = action.indexOf(':', i);
                result[1] = action.substring(j+1);
                result[2] = action.substring(i,j);
                if (context != null) {
                    result[2] = context.getResources().getString(R.string.preference_title_select_shortcut) + ": "+ result[2];
                }

            } else if (action.contains(".")) {
                result[0] = "launcher";
                result[1] = action;
                if (context != null) {
                    try {
                        PackageManager packageManager = context.getPackageManager();
                        ApplicationInfo applicationInfo = packageManager.getApplicationInfo(action, 0);

                        result[2] = (String) packageManager.getApplicationLabel(applicationInfo);

                    } catch (Throwable e) {
                    }
                }

            } else {
                result[0] = "custom";
                result[1] = action;
                if (context != null) {
                    try {
                        for (RemapAction current : Actions.COLLECTION) {
                            if (current.getAction().equals(action)) {
                                result[2] = current.getLabel(context);
                                break;
                            }
                        }
                    } catch (Throwable e) {
                    }
                }
            }
        }
        return result;
    }

    public static String actionToString(Context context, String action) {
        return actionParse(context, action)[2];
    }

	public static String conditionToString(Context context, String condition) {
		Integer id = context.getResources().getIdentifier("condition_type_$" + condition, "string", PACKAGE_NAME);
		
		if (id > 0) {
			return context.getResources().getString(id);
			
		} else {
			try {
				PackageManager packageManager = context.getPackageManager();
				ApplicationInfo applicationInfo = packageManager.getApplicationInfo(condition, 0);
				
				return (String) packageManager.getApplicationLabel(applicationInfo);
				
			} catch(Throwable e) {}
		}
		
		return condition;
	}
	
	public static String keyToString(String keyCode) {
		String[] codes = keyCode.trim().split("[^0-9]+");
		List<String> output = new ArrayList<String>();

        for (String code : codes) {
            if (code != null && !code.equals("0")) {
                output.add(keyToString(Integer.parseInt(code)));
            }
        }
		
		return TextUtils.join("+", output);
	}

	@SuppressLint("NewApi")
    private static String keyToString(Integer keyCode) {
		/*
		 * KeyEvent to string is not supported in Gingerbread, 
		 * so we define the most basics ourself.
		 */
		
		if (keyCode != null) {
			switch (keyCode) {
				case KeyEvent.KEYCODE_VOLUME_UP: return "Volume Up";
				case KeyEvent.KEYCODE_VOLUME_DOWN: return "Volume Down";
				case KeyEvent.KEYCODE_SETTINGS: return "Settings";
				case KeyEvent.KEYCODE_SEARCH: return "Search";
				case KeyEvent.KEYCODE_POWER: return "Power";
				case KeyEvent.KEYCODE_NOTIFICATION: return "Notification";
				case KeyEvent.KEYCODE_MUTE: return "Mic Mute";
				case KeyEvent.KEYCODE_MUSIC: return "Music";
				case KeyEvent.KEYCODE_MOVE_HOME: return "Home";
				case KeyEvent.KEYCODE_MENU: return "Menu";
				case KeyEvent.KEYCODE_MEDIA_STOP: return "Media Stop";
				case KeyEvent.KEYCODE_MEDIA_REWIND: return "Media Rewind";
				case KeyEvent.KEYCODE_MEDIA_RECORD: return "Media Record";
				case KeyEvent.KEYCODE_MEDIA_PREVIOUS: return "Media Previous";
				case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return "Media Play/Pause";
				case KeyEvent.KEYCODE_MEDIA_PLAY: return "Media Play";
				case KeyEvent.KEYCODE_MEDIA_PAUSE: return "Media Pause";
				case KeyEvent.KEYCODE_MEDIA_NEXT: return "Media Next";
				case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: return "Media Fast Forward";
				case KeyEvent.KEYCODE_HOME: return "Home";
				case KeyEvent.KEYCODE_FUNCTION: return "Function";
				case KeyEvent.KEYCODE_FOCUS: return "Camera Focus";
				case KeyEvent.KEYCODE_ENDCALL: return "End Call";
				case KeyEvent.KEYCODE_DPAD_UP: return "DPad Up";
				case KeyEvent.KEYCODE_DPAD_RIGHT: return "DPad Right";
				case KeyEvent.KEYCODE_DPAD_LEFT: return "DPad Left";
				case KeyEvent.KEYCODE_DPAD_DOWN: return "DPad Down";
				case KeyEvent.KEYCODE_DPAD_CENTER: return "DPad Center";
				case KeyEvent.KEYCODE_CAMERA: return "Camera";
				case KeyEvent.KEYCODE_CALL: return "Call";
				case KeyEvent.KEYCODE_BUTTON_START: return "Start";
				case KeyEvent.KEYCODE_BUTTON_SELECT: return "Select";
				case KeyEvent.KEYCODE_BACK: return "Back";
				case KeyEvent.KEYCODE_APP_SWITCH: return "App Switch";
				case KeyEvent.KEYCODE_3D_MODE: return "3D Mode";
				case KeyEvent.KEYCODE_ASSIST: return "Assist";
				case KeyEvent.KEYCODE_PAGE_UP: return "Page Up";
				case KeyEvent.KEYCODE_PAGE_DOWN: return "Page Down";
				case KeyEvent.KEYCODE_HEADSETHOOK: return "Headset Hook";
			}
			
			if (android.os.Build.VERSION.SDK_INT >= 11) {
				switch (keyCode) {
					case KeyEvent.KEYCODE_VOLUME_MUTE: return "Volume Mute";
					case KeyEvent.KEYCODE_ZOOM_OUT: return "Zoom Out";
					case KeyEvent.KEYCODE_ZOOM_IN: return "Zoom In";
				}
			}
			
			if (android.os.Build.VERSION.SDK_INT >= 12) {
				String codeName = KeyEvent.keyCodeToString(keyCode);
				
				if (codeName.startsWith("KEYCODE_")) {
					String[] codeWords = codeName.toLowerCase(Locale.US).split("_");
					StringBuilder builder = new StringBuilder();
					
					for (int i=1; i < codeWords.length; i++) {
						char[] codeChars = codeWords[i].trim().toCharArray();
						
						codeChars[0] = Character.toUpperCase(codeChars[0]);
						
						if (i > 1) {
							builder.append(" ");
						}

						builder.append(codeChars);
					}
					
					return builder.toString();
				}
			}
		}
		
		return "" + keyCode;
	}
	
	public static Integer getConditionIdentifier(Context context, String condition) {
		return context.getResources().getIdentifier("condition_type_$" + condition, "string", Common.PACKAGE_NAME);
	}
	
	/*
	 * Quantity Strings are broken on some Platforms and phones which is described in the below tracker. 
	 * To make up for this, we use this little helper. We don't need options like 'few' or 'many', so
	 * no larger library replacement is needed. 
	 * 
	 * http://code.google.com/p/android/issues/detail?id=8287
	 */
	public static int getQuantityResource(Resources resources, String idRef, int quantity) {
		int id = resources.getIdentifier(idRef + "_$" + quantity, "string", PACKAGE_NAME);
		
		if (id == 0) {
			id = resources.getIdentifier(idRef, "string", PACKAGE_NAME);
		}
		
		return id;
	}

	public static class AppBuilder implements OnScrollListener {
		
		private WeakReference<ListView> mView;
		
		private LruCache<String, Bitmap> mCache;
		
		private List<AppInfo> mApplications = new ArrayList<AppInfo>();
		
		private Integer mViewCount = 0;
		
		public static interface BuildAppView {
			public void onBuildAppView(ListView view, String name, String label);
		}
		
		public AppBuilder(ListView listView) {
			mView = new WeakReference<ListView>(listView);

			mCache = new LruCache<String, Bitmap>( Math.round(0.15f * Runtime.getRuntime().maxMemory() / 1024) ) {
				@Override
				protected int sizeOf(String key, Bitmap value) {
					return (value.getRowBytes() * value.getHeight()) / 1024;
				}
			};
		}
		
		public void destroy() {
			mCache.evictAll();
			mApplications.clear();
			mView.clear();
		}
		
		private Bitmap drawableToBitmap (Drawable drawable) {
			if (!(drawable instanceof BitmapDrawable)) {
				int width = drawable.getIntrinsicWidth();
				int height = drawable.getIntrinsicHeight();
				
				Bitmap bitmap = Bitmap.createBitmap(width > 0 ? width : 1, height > 0 ? height : 1, Config.ARGB_8888);
				Canvas canvas = new Canvas(bitmap);
				
				drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
				drawable.draw(canvas);
				
				return bitmap;
			}
			
			return ((BitmapDrawable) drawable).getBitmap();
		}

		@Override
		public void onScrollStateChanged(AbsListView view, int scrollState) {}

		@Override
		public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
			/*
			 * All pre-defiened preferences has nothing to do with the application list, so we will skip this.
			 * Because Android removed any view not being displayed, we will always start at index 0. 
			 * But since we might have pre-defined preferences at the top, we do not always want to start there. 
			 * It depends on whether or not we currently are at the top.
			 */
			Integer startI = firstVisibleItem > mViewCount ? 0 : mViewCount-firstVisibleItem;
			Integer startX = firstVisibleItem > mViewCount ? firstVisibleItem-mViewCount : 0;
			
			/*
			 * This will load icons on scroll and keep as many as possible in a cache (amount depends on device RAM).
			 * The adaptor used by Android's preferences automatically removes all views that is currently not being displayed. 
			 * This means that we do not have to worry about removing non-used icons for the GC. 
			 */
			for (int i=startI, x=startX; i < visibleItemCount; i++, x++) {
				if (view.getChildAt(i) != null) {
					AppInfo appInfo = mApplications.get(x);
					String name = appInfo.getName();
					Bitmap bitmap = mCache.get(name);
					ImageView imageView = (ImageView) view.getChildAt(i).findViewById(android.R.id.icon);
					
					/*
					 * TODO: Should be add a background thread for loading icons?
					 */
					if (bitmap == null) {
						bitmap = drawableToBitmap( appInfo.loadIcon( view.getContext() ) );
					}
					
					mCache.put(name, bitmap);
					
					imageView.setImageBitmap(bitmap);
				}
			}
		}
		
		public void build(final BuildAppView callback) {
			View view = mView.get();
			
			if (view != null) {
				(new AsyncTask<Context, Void, List<AppInfo>>() {
					
					ProgressDialog mProgress;
					
					@Override
					protected void onPreExecute() {
						View view = mView.get();
						
						if (view != null) {
							mProgress = new ProgressDialog(view.getContext());
							mProgress.setMessage(view.getContext().getResources().getString(R.string.task_applocation_list));
							mProgress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
							mProgress.setCancelable(false);
							mProgress.setCanceledOnTouchOutside(false);
							mProgress.show();
						}
					}
					
					@Override
					protected List<AppInfo> doInBackground(Context... args) {
						Context context = args[0];
						
						Intent intent = new Intent();
						intent.setAction(Intent.ACTION_MAIN);
						intent.addCategory(Intent.CATEGORY_LAUNCHER);
						
						PackageManager packageManager = context.getPackageManager();
						List<ResolveInfo> packages = packageManager.queryIntentActivities(intent, 0);
						
						if (mProgress != null) {
							mProgress.setMax(packages.size());
						}
						
						for(int i=0; i < packages.size(); i++) {
							if (mProgress != null) {
								mProgress.setProgress( (i+1) );
							}
							
							ApplicationInfo app = packages.get(i).activityInfo.applicationInfo;
							String label = (String) packageManager.getApplicationLabel(app);
							
							if (label != null) {
								mApplications.add(new AppInfo(label, app));
							}
						}
						
						Collections.sort(mApplications);
						
						return mApplications;
					}
					
					@Override
					protected void onPostExecute(List<AppInfo> packages) {
						ListView view = mView.get();
						
						if (view != null && mProgress != null && mProgress.isShowing()) {
							mViewCount = view.getCount();
							
							for(int i=0; i < packages.size(); i++) {
								AppInfo appInfo = packages.get(i);
								
								callback.onBuildAppView(view, appInfo.getName(), appInfo.getLabel());
							}

							try {
								mProgress.dismiss();
								
							} catch(Throwable e) {}
							
							/*
							 * We don't display images in GB, so no need to track
							 * scrolling. 
							 */
							if (android.os.Build.VERSION.SDK_INT >= 11) {
								view.setOnScrollListener(AppBuilder.this);
							}
						}
					}
					
				}).execute( view.getContext().getApplicationContext() );
			}
		}
		
		public static class AppInfo implements Comparable<AppInfo> {
			
			private ApplicationInfo mApplicationInfo;
			
			private String mLabel;
			
			private AppInfo(String label, ApplicationInfo app) {
				mApplicationInfo = app;
				mLabel = label;
			}
			
			@Override
			public int compareTo(AppInfo comparible) {
				return Collator.getInstance(Locale.getDefault()).
						compare(mLabel, comparible.mLabel);
			}
			
			public Drawable loadIcon(Context context) {
				return context.getPackageManager().getApplicationIcon(mApplicationInfo);
			}
			
			public String getName() {
				return mApplicationInfo.packageName;
			}
			
			public String getLabel() {
				return mLabel;
			}
		}
	}
	
	public static Boolean debug() {
		if (ENABLE_DEBUG == null) {
			/*
			 * Avoid recursive calls
			 */
			ENABLE_DEBUG = false;
			
			XServiceManager preferences = XServiceManager.getInstance();
			
			if (preferences != null && preferences.isServiceReady()) {
				ENABLE_DEBUG = preferences.getBoolean(Settings.DEBUG_ENABLE_LOGGING);
				
			} else {
				ENABLE_DEBUG = null;
			}
		}
		
		return DEBUG || ENABLE_DEBUG == null || ENABLE_DEBUG;
	}

	public static class PlaceHolder {
		private final String mKey;
		
		public PlaceHolder(String key) {
			mKey = key;
		}
		
		public String get(Object... replacements) {
			return String.format(mKey, replacements);
		}
	}
	
	public static class RemapAction {
		private final Boolean mDispatchAction;
		private final String mAction;
		private final Integer mMinSDK;
		private final Integer mLabelRes;
		private final Integer mDescRes;
		private final Integer mAlertRes;
		private final Integer mNoticeRes;
		
		private final List<String> mConditionBlacklist = new ArrayList<String>();
		
		private final Validate mValidator;
		
		public RemapAction(String name, Integer sdk, Integer labelRes, Integer descriptionRes, Integer alertRes, Integer noticeRes, Object... blacklist) {
			mDispatchAction = name.matches("^[0-9]+$");
			mAction = name;
			mMinSDK = sdk;
			mLabelRes = labelRes;
			mDescRes = descriptionRes;
			mAlertRes = alertRes;
			mNoticeRes = noticeRes;
			mValidator = blacklist.length > 0 && blacklist[ blacklist.length-1 ] instanceof Validate ? (Validate) blacklist[ blacklist.length-1 ] : null;

            for (Object aBlacklist : blacklist) {
                if (aBlacklist instanceof String) {
                    mConditionBlacklist.add((String) aBlacklist);
                }
            }
		}
		
		public RemapAction(Integer key, Integer sdk, Integer labelRes, Integer descriptionRes, Integer alertRes, Integer noticeRes, Object... blacklist) {
			this(""+key, sdk, labelRes, descriptionRes, alertRes, noticeRes, blacklist);
		}
		
		public String getAction() {
			return mAction;
		}
		
		public String getLabel(Context context) {
			if (mLabelRes > 0) {
				return context.getResources().getString(mLabelRes);
			}
			
			return keyToString(mAction);
		}
		
		public String getDescription(Context context) {
			if (mDescRes > 0) {
				return context.getResources().getString(mDescRes);
			}
			
			return null;
		}
		
		public String getNotice(Context context) {
			if (mNoticeRes > 0) {
				return context.getResources().getString(mNoticeRes);
			}
			
			return null;
		}
		
		public String getAlert(Context context) {
			if (mAlertRes > 0) {
				return context.getResources().getString(mAlertRes);
			}
			
			return null;
		}
		
		public Boolean isDispatchAction() {
			return mDispatchAction;
		}
		

		public Boolean hasAlert(Context context) {
			return mAlertRes > 0 && (mValidator == null || mValidator.onDisplayAlert(context));
		}
		
		public Boolean isValid(Context context, String condition) {
			return android.os.Build.VERSION.SDK_INT >= mMinSDK && !mConditionBlacklist.contains(condition) && (mValidator == null || mValidator.onValidate(context));
		}
		
		public static abstract class Validate {
			public Boolean onValidate(Context context) { return true; }
			public Boolean onDisplayAlert(Context context) { return false; }
			// --Commented out by Inspection (2015-02-15 15:28):public Boolean onDisplayNotice(Context context) { return true; }
		}
	}
}
