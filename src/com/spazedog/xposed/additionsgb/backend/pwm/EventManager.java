package com.spazedog.xposed.additionsgb.backend.pwm;

import java.util.ArrayList;
import java.util.List;

import android.annotation.SuppressLint;
import android.util.Log;
import android.view.ViewConfiguration;

import com.spazedog.lib.reflecttools.ReflectClass;
import com.spazedog.xposed.additionsgb.Common;
import com.spazedog.xposed.additionsgb.backend.pwm.iface.IEventMediator;
import com.spazedog.xposed.additionsgb.backend.service.XServiceManager;
import com.spazedog.xposed.additionsgb.configs.Settings;
import com.spazedog.xposed.additionsgb.tools.MapList;

public final class EventManager extends IEventMediator {
	
	public static enum State { PENDING, ONGOING, REPEATING, INVOKED }
	
	private final MapList<Integer, EventKey> mEventKeys = new MapList<Integer, EventKey>();
	private final List<EventKey> mKeyCache = new ArrayList<EventKey>();
	
	private Integer mLastQueued = 0;
	public State mState = State.PENDING;
	private Integer mTapCount = 0;
	private Long mEventTime = 0L;
	
	private Boolean mIsScreenOn = true;
	private Boolean mIsExtended = false;
	private Boolean mIsCallButton = false;
	private Integer mTapTimeout = 0;
	private Integer mPressTimeout = 0;
	
	protected static final int maxActions = 3 * IEventMediator.ActionType.values().length;
	//actions in the order they appear: press 1, tap 1, press 2, tap 2 etc
	private String[] mKeyActions = new String[maxActions];

	private final Object mEventLock = new Object();

	protected EventManager(ReflectClass pwm, XServiceManager xServiceManager) {
		super(pwm, xServiceManager);
	}
	
	private EventKey initiateEventKey(Integer keyCode, Boolean isKeyDown, Integer policyFlags, Integer metaState, Long downTime) {
		synchronized(mEventLock) {
			EventKey eventKey = mEventKeys.get(keyCode);
			
			if (eventKey == null) {
				eventKey = mKeyCache.size() > 0 ? mKeyCache.remove(0) : new EventKey(this);

				mEventKeys.put(keyCode, eventKey);
			}
			
			if (isKeyDown) {
				eventKey.initiateInstance(keyCode, fixPolicyFlags(keyCode, policyFlags), metaState, downTime);
			}
			
			eventKey.updateInstance(isKeyDown);
			
			return eventKey;
		}
	}
	
	private void recycleEventKeys() {
		synchronized(mEventLock) {
			for (Integer key : mEventKeys.keySet()) {
				mKeyCache.add(mEventKeys.get(key));
			}
			
			mEventKeys.clear();
		}
	}
	
	public Boolean registerKey(Integer keyCode, Boolean isKeyDown, Boolean isScreenOn, Integer policyFlags, Integer metaState, Long downTime, Long eventTime) {
		synchronized(mEventLock) {
			if (isKeyDown && (eventTime - mEventTime) > 1500) { // 1000 + Default Android Long Press timeout
				releaseAllKeys();
				recycleEventKeys();
			}
			
			mLastQueued = keyCode;
			mEventTime = eventTime;
			Boolean newEvent = false;
			Boolean newKey = !mEventKeys.containsKey(keyCode);
			
			initiateEventKey(keyCode, isKeyDown, policyFlags, metaState, downTime);
			
			if (isKeyDown) {
				if (mState == State.ONGOING && !newKey) {
					if(Common.debug()) Log.d(TAG, "Registering new tap event");
					
					mTapCount += 1;
					
				} else if (hasState(State.ONGOING, State.INVOKED) && getKeyCount() > 1 && isDownEvent()) {
					if(Common.debug()) Log.d(TAG, "Registering new combo event");
					
					mTapCount = 0;
					newEvent = true;
					
				} else {
					if(Common.debug()) Log.d(TAG, "Registering new single event");
					
					if (getKeyCount() > 1) {
						recycleEventKeys();
						initiateEventKey(keyCode, isKeyDown, policyFlags, metaState, downTime);
					}
					
					mTapCount = 0;
					newEvent = true;
				}

				if (newEvent) {
					String configName = mEventKeys.joinKeys(":");
					
					if (mEventKeys.size() == 1) {
						configName += ":0";
					}
					
					if(Common.debug()) Log.d(TAG, "Getting actions for the key combo '" + configName + "'");
					
					mIsScreenOn = isScreenOn;
					mIsExtended = mXServiceManager.isPackageUnlocked();
					mIsCallButton = mXServiceManager.getBooleanGroup(Settings.REMAP_KEY_ENABLE_CALLBTN, configName);
					mTapTimeout = mXServiceManager.getInt(Settings.REMAP_TIMEOUT_DOUBLECLICK, ViewConfiguration.getDoubleTapTimeout());
					mPressTimeout = mXServiceManager.getInt(Settings.REMAP_TIMEOUT_LONGPRESS, ViewConfiguration.getLongPressTimeout());

					String appCondition = !isScreenOn ? null : isKeyguardShowing() ? "guard" : mIsExtended ? getPackageNameFromStack(0, StackAction.INCLUDE_HOME) : null;
					List<String> actions = null;
					if (appCondition != null && mIsExtended) {
						actions = mXServiceManager.getStringArrayGroup(Settings.REMAP_KEY_LIST_ACTIONS.get(appCondition), configName, null);
					}
					if(actions == null) {
						actions = mXServiceManager.getStringArrayGroup(Settings.REMAP_KEY_LIST_ACTIONS.get(isScreenOn ? "on" : "off"), configName, null);
					}
					if (actions == null) {
						actions = new ArrayList<String>();
					}
					
					/*
					 * TODO: Update the config file to produce the same output as convertOldConfig()
					 */
					mKeyActions = convertOldConfig(actions);

					if (!mIsExtended) {
						for (int i=0; i < maxActions; i++) {
							/*
							 * Only include Click and Long Press along with excluding Application Launch on non-pro versions
							 */
							if (getKeyCount() != 1 || i >= 2 || (mKeyActions[i] != null && !mKeyActions[i].matches("^[a-z0-9_]+$"))) {
								mKeyActions[i] = null;
							}
						}
					}
					
				} else {
					mIsCallButton = false;
				}
			}
			
			return newEvent;
		}
	}
	
	@SuppressLint("Assert")
	private String[] convertOldConfig(List<String> oldConfig) {
		/*
		 * This is a tmp method that will be used until
		 * such time where the config file is updated to produce
		 * the same output.
		 * 
		 * TODO: Remove this method
		 */
		
		/*
		 *  - 0 = Click
		 *  - 1 = Double Click
		 *  - 2 = Long Press
		 *  - 3 = Double Long Press
		 *  - 4 = Triple Click
		 *  - 5 = Triple Long Press
		 */
		Integer[] newLocations = new Integer[]{2,0,3,1,5,4};
		assert maxActions == newLocations.length;
		String[] newConfig = new String[newLocations.length];
		
		for (int i=0; i < newLocations.length; i++) {
			Integer x = newLocations[i];
			newConfig[i] = (oldConfig.size() > x ? oldConfig.get(x) : null);
		}
		
		return newConfig;
	}
	
	public Boolean isDownEvent() {
		Integer count = mEventKeys.size();
		
		for (Integer key : mEventKeys.keySet()) {
			if (!mEventKeys.get(key).isPressed()) {
				return false;
			}
		}
		
		return count > 0;
	}
	
	public Integer getTapCount() {
		return mTapCount;
	}
	
	public Long getEventTime() {
		return mEventTime;
	}
	
	public Integer getKeyCount() {
		return mEventKeys.size();
	}
	
	public EventKey getKey(Integer keyCode) {
		return mEventKeys.get(keyCode);
	}
	
	public EventKey getKeyAt(Integer keyIndex) {
		return mEventKeys.getAt(keyIndex);
	}
	
	public Boolean isCallButton() {
		return mIsCallButton;
	}
	
	public Boolean isExtended() {
		return mIsExtended;
	}
	
	public Boolean isScreenOn() {
		return mIsScreenOn;
	}
	
	public Integer getPressTimeout() {
		return mPressTimeout;
	}
	
	public Integer getTapTimeout() {
		return mTapTimeout;
	}

	private int getActionIndex(final ActionType atype) {
		int index = mTapCount * 2;
		if (atype == ActionType.CLICK) {index++;}
		return index;
	}

	public String getAction(final ActionType atype) {
		final int index = getActionIndex(atype);
		if (index >= mKeyActions.length) { return null; }
		return mKeyActions[index];
	}
	
	public Boolean hasMoreActions() {
		int index = 1 + getActionIndex(ActionType.CLICK);
		while (index < maxActions) {
			if (mKeyActions[index] != null) {
				return true;
			}
			index++;
		}
		return false;
	}
	
	public State setState(State state) {
		State oldState = mState;
		mState = state;
		
		return oldState;
	}
	
	public Boolean hasState(State... states) {
		for (int i=0; i < states.length; i++) {
			if (mState == states[i]) {
				return true;
			}
		}
		
		return false;
	}
	
	public Integer getKeyCodePosition(Integer keyCode) {
		return mEventKeys.indexOf(keyCode);
	}
	
	public Integer getLastQueuedKeyCode() {
		return mLastQueued;
	}
	
	public void releaseAllKeys() {
		for (int i=0; i < mEventKeys.size(); i++) {
			mEventKeys.getAt(i).release();
		}
	}
	
	public Boolean waitForChange(Integer timeout) {
		Long lastEventTime = mEventTime;
		
		do {
			try {
				Thread.sleep(1);
				
			} catch (Throwable e) {}
			
			timeout -= 1;
			
		} while (lastEventTime.equals(mEventTime) && timeout > 0);
		
		return timeout <= 0;
	}
}
