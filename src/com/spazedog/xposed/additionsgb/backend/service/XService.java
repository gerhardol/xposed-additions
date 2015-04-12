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

package com.spazedog.xposed.additionsgb.backend.service;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.spazedog.lib.reflecttools.ReflectClass;
import com.spazedog.lib.reflecttools.ReflectMethod;
import com.spazedog.lib.reflecttools.utils.ReflectConstants.Match;
import com.spazedog.xposed.additionsgb.Common;
import com.spazedog.xposed.additionsgb.utils.SettingsHelper;
import com.spazedog.xposed.additionsgb.utils.SettingsHelper.SettingsData;
import com.spazedog.xposed.additionsgb.utils.SettingsHelper.Type;
import com.spazedog.xposed.additionsgb.utils.android.ContextHelper;
import com.spazedog.xposed.additionsgb.utils.android.XmlUtilsHelper;

import de.robv.android.xposed.XC_MethodHook;

public final class XService extends IXService.Stub {
	private static final String TAG = XService.class.getName();
	
	private Context mContextSystem;

	private SettingsData mData = new SettingsData();
	
	private Boolean mIsReady = false;
	
	private Integer mVersion = 0;
	
	private final Set<IBinder> mListeners = new HashSet<IBinder>();
	
	private static class PREFERENCE {
		private static final File ROOT = new File(Environment.getDataDirectory(), "data/" + Common.PACKAGE_NAME);
		private static final File DIR = new File(ROOT.getPath(), "shared_prefs");
		private static final File FILE = new File(DIR.getPath(), Common.PREFERENCE_FILE + ".xml");
		private static int UID = 1000;
		private static final int GID = 1000;
	}
	
	public static void init() {
		Log.d(TAG, "Adding Service Hooks on SDK version " + Build.VERSION.SDK_INT);
		
		/*
		 * Plug in the service into Android's service manager
		 */
		if (Build.VERSION.SDK_INT < 21) {
			Log.d(TAG, "Attaching hook to ActivityManagerService");
			
			XService hooks = new XService();
			ReflectClass ams = ReflectClass.forName("com.android.server.am.ActivityManagerService");
			
			ams.inject("main", hooks.hook_main);
			ams.inject("systemReady", hooks.hook_systemReady);
			ams.inject("shutdown", hooks.hook_shutdown);
			
		} else {
			Log.d(TAG, "Attaching hook to ActivityThread to get a proper ClassLoader");
			
			/*
			 * On API 21 we cannot access certain classes with the boot class loader. 
			 * So we need to go another way. 
			 */
			
			ReflectClass at = ReflectClass.forName("android.app.ActivityThread");
			at.inject("systemMain", new XC_MethodHook() {
				@Override
				protected final void afterHookedMethod(final MethodHookParam param) {
					Log.d(TAG, "Attaching hook to ActivityManagerService and SystemServer");
					
					XService hooks = new XService();
					ReflectClass ams = ReflectClass.forName("com.android.server.am.ActivityManagerService", Thread.currentThread().getContextClassLoader());
					ReflectClass ss = ReflectClass.forName("com.android.server.SystemServer", Thread.currentThread().getContextClassLoader());
					
					ss.inject("startBootstrapServices", hooks.hook_main);
					ams.inject("systemReady", hooks.hook_systemReady);
					ams.inject("shutdown", hooks.hook_shutdown);
				}
			});
		}
	
		/*
		 * If we have a configuration file, make sure that the system user can read it.
		 * Write access does not mater, from Android 5.0 and up, SELinux will block this regardless of the permissions.
		 */
		if (PREFERENCE.FILE.exists()) {
			/*
			 * Some Android versions fail when trying to change the ownership. As a fallback we leave the ownership unchanged, and just makes
			 * sure that the file is globally readable and writable.
			 */
			
			ReflectMethod setPermissions = ReflectClass.forName("android.os.FileUtils")
					.findMethod("setPermissions", Match.BEST, String.class, Integer.TYPE, Integer.TYPE, Integer.TYPE);
			
			if((Integer) setPermissions.invoke(PREFERENCE.FILE.getPath(), 0640, -1, PREFERENCE.GID) != 0) {
				setPermissions.invoke(PREFERENCE.FILE.getPath(), 0644, -1, -1);
			}
		}
	}
	
	protected XC_MethodHook hook_main = new XC_MethodHook() {
		@Override
		protected final void afterHookedMethod(final MethodHookParam param) {
			Log.d(TAG, "Entering Service Main hook");
			
			if (Build.VERSION.SDK_INT < 21) {
				/*
				 * The original com.android.server.am.ActivityManagerService.main() method
				 * will return the system context, which XposedBridge will have stored in param.getResult().
				 * This is why we inject this as an After Hook.
				 */
				mContextSystem = (Context) param.getResult();
				
				ReflectClass.forName("android.os.ServiceManager")
				.findMethod("addService", Match.BEST, String.class, IBinder.class)
				.invoke(Common.XSERVICE_NAME, XService.this);
				
			} else {
				/*
				 * The original android.app.ActivityThread method will return a new instance of itself. 
				 * This instance contains the system context.
				 */
				mContextSystem = (Context) ReflectClass.forReceiver(param.thisObject).findField("mSystemContext").getValue();
				
				/*
				 * Set the class loader for the server process. 
				 * 
				 * TODO: Maybe this should just be added as standard to ReflectTools
				 */
				ReflectClass.setClassLoader(Thread.currentThread().getContextClassLoader());
				
				ReflectClass.forName("android.os.ServiceManager")
				.findMethod("addService", Match.BEST, String.class, IBinder.class, Boolean.TYPE)
				.invoke(Common.XSERVICE_NAME, XService.this, true);
			}
		}
	};
	
	protected XC_MethodHook hook_systemReady = new XC_MethodHook() {
		@Override
		@SuppressWarnings("unchecked")
		protected final void afterHookedMethod(final MethodHookParam param) {
			if(Common.DEBUG) Log.d(TAG, "Starting the service");
			
			try {
				Context mContextModule = mContextSystem.createPackageContext(Common.PACKAGE_NAME, Context.CONTEXT_RESTRICTED);
				
				/*
				 * Make sure that we have the correct UID when checking access later on
				 */
				PREFERENCE.UID = mContextModule.getApplicationInfo().uid;
				
			} catch (NameNotFoundException e1) { e1.printStackTrace(); }
			
			try {
				mVersion = mContextSystem.getPackageManager().getPackageInfo(Common.PACKAGE_NAME, 0).versionCode;
				
			} catch (NameNotFoundException e) { e.printStackTrace(); }
			
			if (PREFERENCE.FILE.exists()) {
				try {
					BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(PREFERENCE.FILE), 16*1024);
					Map<String, Object> data = (Map<String, Object>) XmlUtilsHelper.readMapXml(inputStream);
					
					if (data != null) {
						mData = new SettingsData(data);

						SettingsHelper.unpack(mData);
					}
					
					try {
						inputStream.close();
						
					} catch (IOException e) {}

				} catch (FileNotFoundException e) {
					Log.e(TAG, e.getMessage(), e);
				}
			}
			
			IntentFilter intentFilter = new IntentFilter();
			intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
			intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
			intentFilter.addDataScheme("package");
			
			mContextSystem.registerReceiver(applicationNotifier, intentFilter);
			
			mIsReady = true;
		}
	};
	
	protected XC_MethodHook hook_shutdown = new XC_MethodHook() {
		@Override
		protected final void beforeHookedMethod(final MethodHookParam param) {
			if(Common.DEBUG) Log.d(TAG, "Stopping the service");
			
			apply();
		}
	};
	
	private Boolean accessGranted() {
		/*
		 * By default we allow access to Android and our own module. Others will need to include our permission
		 */
		return Binder.getCallingUid() == PREFERENCE.GID || Binder.getCallingUid() == PREFERENCE.UID || 
				mContextSystem.checkCallingPermission(Common.XSERVICE_PERMISSIONS) == PackageManager.PERMISSION_GRANTED;
	}
	
	private void setCached(String key, Object value, Integer preserve) {
		synchronized (mData) {
			if (accessGranted()) {
				mData.put(key, value, preserve == 1);
				
				broadcastChange(key);
			}
		}
	}
	
	private Object getCached(String key, Object defaultValue, Integer type) {
		if (mData.contains(key)) {
			return mData.get(key);
		}
		
		PackageManager manager = mContextSystem.getPackageManager();
		
		try {
			Resources resources = manager.getResourcesForApplication(Common.PACKAGE_NAME);
			Integer resourceId = resources.getIdentifier(key, Type.getIdentifier(type), Common.PACKAGE_NAME);
			
			if (resourceId > 0) {
				switch (type) {
					case Type.STRING: 
						return resources.getString(resourceId);
						
					case Type.LIST:
						String[] array = resources.getStringArray(resourceId);
						List<String> list = new ArrayList<String>();

						Collections.addAll(list, array);
						
						return list;
		
					case Type.BOOLEAN:
						return resources.getBoolean(resourceId);
		
					case Type.INTEGER:
						return resources.getInteger(resourceId);
				}
			} 
			
		} catch (NameNotFoundException e) { 
			Log.e(TAG, "Could not access the application resources!"); 
		}
		
		return defaultValue;
	}
	
	@Override
	public void putString(String key, String value, int preserve) throws RemoteException {
		setCached(key, value, preserve);
	}
	
	@Override
	public void putStringArray(String key, List<String> value, int preserve) throws RemoteException {
		setCached(key, value, preserve);
	}
	
	@Override
	public void putInt(String key, int value, int preserve) throws RemoteException {
		setCached(key, value, preserve);
	}
	
	@Override
	public void putBoolean(String key, boolean value, int preserve) throws RemoteException {
		setCached(key, value, preserve);
	}

	@Override
	public String getString(String key, String defaultValue) throws RemoteException {
		return (String) getCached(key, defaultValue, Type.STRING);
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public List<String> getStringArray(String key, List<String> defaultValue) throws RemoteException {
		return (List<String>) getCached(key, defaultValue, Type.LIST);
	}

	@Override
	public int getInt(String key, int defaultValue) throws RemoteException {
		return (Integer) getCached(key, defaultValue, Type.INTEGER);
	}

	@Override
	public boolean getBoolean(String key, boolean defaultValue) throws RemoteException {
		return (Boolean) getCached(key, defaultValue, Type.BOOLEAN);
	}
	
	@Override
	public boolean remove(String key) {
		if (mData.contains(key)) {
			mData.remove(key);
			
			broadcastChange(key);
			
			return true;
		}
		
		return false;
	}
	
	@Override
	public int getType(String key) {
		if (mData.contains(key)) {
			mData.type(key);
		}
		
		return Type.UNKNOWN;
	}
	
	@Override
	public List<String> getKeys() {
		ArrayList<String> list = new ArrayList<String>();
		
		for (String key : mData.keySet()) {
			list.add(key);
		}
		
		return list;
	}
	
	@Override 
	public List<String> getPreservedKeys() {
		ArrayList<String> list = new ArrayList<String>();
		
		for (String key : mData.keySet()) {
			if (mData.persistent(key)) {
				list.add(key);
			}
		}
		
		return list;
	}
	
	@Override
	public void apply() {
		write();
	}
	
	@SuppressLint("NewApi")
	private void write() {
		synchronized (mData) {
			if(Common.DEBUG) Log.d(TAG, "Preparing configuration file for writing");

			if (mData.changed()) {
				Intent intent = new Intent( Common.XSERVICE_BROADCAST_SETTINGS );
				Bundle bundle = new Bundle();
				SettingsData data = new SettingsData();
				
				/*
				 * pack all data into the extras bundle
				 */
				for (String key : mData.keySet()) {
					if (mData.persistent(key)) {
						data.put(key, mData.get(key));
					}
				}
	
				SettingsHelper.pack(data);
				bundle.putParcelable("data", data);
				intent.putExtras(bundle);
				
				/*
				 * send the data to the application so that it can write it to disk
				 */
				
				ContextHelper.sendBroadcast(mContextSystem, intent);
			}
		}
	}
	
	@Override
	public boolean isUnlocked() {
		return mContextSystem.getPackageManager()
				.checkSignatures(Common.PACKAGE_NAME, Common.PACKAGE_NAME_PRO) == PackageManager.SIGNATURE_MATCH;
	}
	
	@Override
	public boolean isReady() {
		return mIsReady;
	}
	
	@Override
	public int getVersion() {
		return mVersion;
	}
	
	@Override
	public void setOnChangeListener(IXServiceChangeListener listener) throws RemoteException {
		final IBinder binder = listener.asBinder();
		binder.linkToDeath(new DeathRecipient(){
			@Override
			public void binderDied() {
				synchronized(mListeners) {
					binder.unlinkToDeath(this, 0);
					
					mListeners.remove(binder);
				}
			}
			
		}, 0);
		
		synchronized(mListeners) {
			if (!mListeners.contains(binder)) {
				mListeners.add(binder);
			}
		}
	}
	
	@Override
	public void sendBroadcast(String action, Bundle data) {
		synchronized(mListeners) {
			for (IBinder listener : mListeners) {
				if (listener != null && listener.pingBinder()) {
					try {
						IXServiceChangeListener.Stub.asInterface(listener).onBroadcastReceive(action, data);
						
					} catch (RemoteException e) {}
				}
			}
		}
	}
	
	protected BroadcastReceiver applicationNotifier = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			synchronized(mListeners) {
				for (IBinder listener : mListeners) {
					if (listener != null && listener.pingBinder()) {
						try {
							IXServiceChangeListener.Stub.asInterface(listener).onPackageChanged();
							
						} catch (RemoteException e) {}
					}
				}
			}
		}
	};
	
	private void broadcastChange(String key) {
		Integer type = mData.contains(key) ? mData.type(key) : Type.UNKNOWN;
		
		synchronized(mListeners) {
			for (IBinder listener : mListeners) {
				if (listener != null && listener.pingBinder()) {
					try {
						if (type == Type.UNKNOWN) {
							IXServiceChangeListener.Stub.asInterface(listener).onPreferenceRemoved(key);
							
						} else {
							IXServiceChangeListener.Stub.asInterface(listener).onPreferenceChanged(key, type);
						}
						
					} catch (RemoteException e) {}
				}
			}
		}
	}
}
