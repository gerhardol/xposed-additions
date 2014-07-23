package com.spazedog.xposed.additionsgb.backend.pwm;

import java.util.ArrayList;
import java.util.List;

import com.spazedog.xposed.additionsgb.backend.service.XServiceManager;
import com.spazedog.xposed.additionsgb.configs.Settings;

import android.os.SystemClock;
import android.view.ViewConfiguration;

public class EventManager {
	public static enum ActionType { CLICK, PRESS }
	public static enum State { PENDING, ONGOING, INVOKED, CANCELED }
	public static enum LongPressType { NONE, DEFAULT_ACTION, CUSTOM_ACTION }
	public static enum Priority { PRIMARY, SECONDARY, INVOKED }

	private XServiceManager mXServiceManager;

	private List<Integer> mOnGoingKeyCodes = new ArrayList<Integer>();

	private Integer mTapTimeout = 0;
	private Integer mPressTimeout = 0;

	protected static final int maxActions = 3*2; //ActionTypes.values().length;

	//actions in the order they appear: press 1, tap 1, press 2, tap 2 etc
	private String[] mActions = new String[maxActions];

	private Boolean mIsCallButton = false;
	private Boolean mIsExtended = false;

	private State mState = State.PENDING;
	private LongPressType mLongPress = LongPressType.NONE;
	private Integer mLastQueuedKey = 0;
	private Boolean mIsDownEvent = false;
	private Boolean mIsCombiEvent = false;
	private Integer mTapCount = 0;
	private Long mEventTime = 0L;
	private Long mDownTime = 0L;

	private Boolean mIsScreenOn = true;
	private String mCurrentApplication;

	private final EventKey mPrimaryKey = new EventKey(Priority.PRIMARY);
	private final EventKey mSecondaryKey = new EventKey(Priority.SECONDARY);
	private final EventKey mInvokedKey = new EventKey(Priority.INVOKED);

	private final Object mLock = new Object();

	public EventManager(XServiceManager xserviceManager) {
		mXServiceManager = xserviceManager;
	}

	public void registerEvent(String currentApplication, Boolean inKeyguard, Boolean isScreenOn) {
		synchronized (mLock) {
			mIsExtended = mXServiceManager.isPackageUnlocked();
			mIsCallButton = mXServiceManager.getBooleanGroup(Settings.REMAP_KEY_ENABLE_CALLBTN, (mPrimaryKey.mKeyCode + ":" + mSecondaryKey.mKeyCode));
			mPressTimeout = mXServiceManager.getInt(Settings.REMAP_TIMEOUT_LONGPRESS, ViewConfiguration.getLongPressTimeout());
			if (this.mPressTimeout <= 0) {
				this.mPressTimeout = ViewConfiguration.getLongPressTimeout();
			}
			mTapTimeout = mXServiceManager.getInt(Settings.REMAP_TIMEOUT_DOUBLECLICK, ViewConfiguration.getDoubleTapTimeout());
			if (this.mTapTimeout == 0) {
				this.mTapTimeout = ViewConfiguration.getDoubleTapTimeout();
			} else if (this.mTapTimeout < 0) {
				this.mTapTimeout = this.mPressTimeout;
			}
			mCurrentApplication = inKeyguard ? "keyguard" : currentApplication;
			mIsScreenOn = isScreenOn;
			/*
			/*
			 * This array is to help extract the actions from the preference array.
			 * Since triple actions was not added until later, these was placed at the end to 
			 * keep compatibility with already existing preference files. 
			 * 
			 *  - 0 = Click
			 *  - 1 = Double Click
			 *  - 2 = Long Press
			 *  - 3 = Double Long Press
			 *  - 4 = Triple Click
			 *  - 5 = Triple Long Press
			 */
			Integer[] oldConfig = new Integer[]{2,0,3,1,5,4};
			String keyGroupName = mPrimaryKey.mKeyCode + ":" + mSecondaryKey.mKeyCode;
			String appCondition = !isScreenOn ? null : inKeyguard ? "guard" : mIsExtended ? currentApplication : null;
			List<String> actions = appCondition != null ? mXServiceManager.getStringArrayGroup(Settings.REMAP_KEY_LIST_ACTIONS.get(appCondition), keyGroupName, null) : null;

			if ((mIsCombiEvent && !mIsExtended) || (actions == null && (actions = mXServiceManager.getStringArrayGroup(Settings.REMAP_KEY_LIST_ACTIONS.get(isScreenOn ? "on" : "off"), keyGroupName, null)) == null)) {
				actions = new ArrayList<String>();
			}

			for (int i=0; i < oldConfig.length; i++) {
				Integer x = oldConfig[i];
				/*
				/*
				 * Only include Click and Long Press along with excluding Application Launch on non-pro versions
				 */
				String action = ((i < 2) || mIsExtended) && actions.size() > x && (mIsExtended || !".".equals(actions.get(x))) ? actions.get(x) : null;

				mActions[i] = action;
			}
		}
	}

	public Boolean registerKey(Integer keyCode, Boolean isKeyDown, Integer policyFlags) {
		synchronized (mLock) {
			Long time = SystemClock.uptimeMillis();
			Boolean newEvent = false;

			if (isKeyDown) {
				if ((time - mEventTime) > (1000 + (mPressTimeout * 2))) {
					mState = State.PENDING;
				}

				if (mState == State.ONGOING && ((!mIsCombiEvent && keyCode.equals(mPrimaryKey.mKeyCode)) || (mIsCombiEvent && keyCode.equals(mSecondaryKey.mKeyCode)))) {

					if (keyCode == mSecondaryKey.mKeyCode) {
						mSecondaryKey.mIsKeyDown = true;

					} else {
						mPrimaryKey.mIsKeyDown = true;
						//Increase repeat on primary only
						mTapCount += 1;
					}

				} else if (mState != State.CANCELED && mState != State.PENDING && mPrimaryKey.isKeyDown() && !keyCode.equals(mPrimaryKey.mKeyCode) && (mSecondaryKey.mKeyCode.equals(0) || keyCode.equals(mSecondaryKey.mKeyCode))) {
					if (!keyCode.equals(mSecondaryKey.mKeyCode)) {
						newEvent = true;
					}
					
					mState = State.ONGOING;
					mTapCount = 0;
					mIsCombiEvent = true;
					
					mSecondaryKey.mKeyCode = keyCode;
					mSecondaryKey.mPolicyFlags = policyFlags;
					mSecondaryKey.mIsKeyDown = true;
					
				} else {
					mState = State.ONGOING != mState ? State.ONGOING : State.CANCELED;
					
					if (State.ONGOING == mState) {
						mTapCount = 0;
						mDownTime = time;
						mIsCombiEvent = false;
						mLongPress = LongPressType.NONE;

						mPrimaryKey.mKeyCode = keyCode;
						mPrimaryKey.mPolicyFlags = policyFlags;
						mPrimaryKey.mIsKeyDown = true;
						
						mSecondaryKey.mKeyCode = 0;
						mSecondaryKey.mPolicyFlags = 0;
						mSecondaryKey.mIsKeyDown = false;
						
						mInvokedKey.mKeyCode = 0;

						newEvent = true;
						
					} else {
						return false;
					}
				}
				
				if (newEvent) {
					mIsCallButton = false;
				}
				
				if (mTapCount == 0 || mPrimaryKey.mIsKeyDown) {
					mIsDownEvent = true;
				} else {


					mIsDownEvent = false;
				}
				mIsCallButton = false;

			} else {
				if (keyCode.equals(mSecondaryKey.mKeyCode)) {
					//Note: No sequence checks on key up, the first occurrence is used, the second is possibly ignored
					mSecondaryKey.mIsKeyDown = false;
					
				} else {
					mPrimaryKey.mIsKeyDown = false;
				}
				
				mIsDownEvent = false;
			}
			
			mEventTime = time;
			
			this.mLastQueuedKey = keyCode;
			
			return newEvent;
		} 
	}
	
	public EventKey getEventKey(Integer keyCode) {
		return mPrimaryKey.getKeyCode().equals(keyCode) ? mPrimaryKey :
			mSecondaryKey.getKeyCode().equals(keyCode) ? mSecondaryKey :
			mInvokedKey.getKeyCode().equals(keyCode) ? mInvokedKey : null;
	}

	public EventKey getEventKey(Priority priority) {
		switch (priority) {
		case PRIMARY: return mPrimaryKey;
		case SECONDARY: return mSecondaryKey;
		case INVOKED: return mInvokedKey;
		}
		
		return null;
	}

	public EventKey getParentEventKey(Integer keyCode) {
		return mPrimaryKey.getKeyCode().equals(keyCode) ? mSecondaryKey : 
				mSecondaryKey.getKeyCode().equals(keyCode) ? mPrimaryKey : null;
	}

	public EventKey getParentEventKey(Priority priority) {
		switch (priority) {
		case PRIMARY: return mSecondaryKey;
		case SECONDARY: return mPrimaryKey;
		default:
			break;
		}
		
		return null;
	}
	
	public Boolean isCombiEvent() {
		return mIsCombiEvent;
	}
	
	public Boolean isDownEvent() {
		return mIsDownEvent;
	}
	
	public Boolean isCallButtonEvent() {
		return mIsCallButton;
	}
	
	public Boolean isScreenOn() {
		return mIsScreenOn;
	}
	
	public Boolean hasExtendedFeatures() {
		return mIsExtended;
	}
	
	public Boolean hasOngoingKeyCodes() {
		return mOnGoingKeyCodes.size() > 0;
	}

	public Boolean hasOngoingKeyCodes(Integer keyCode) {
		return mOnGoingKeyCodes.contains((Object) keyCode);
	}

	public Integer[] clearOngoingKeyCodes(Boolean returList) {
		Integer[] keys = null; 
		
		if (returList) {
			keys = mOnGoingKeyCodes.toArray(new Integer[mOnGoingKeyCodes.size()]);
		}
		
		mOnGoingKeyCodes.clear();
		
		return keys;
	}

	public void addOngoingKeyCode(Integer keyCode) {
		if (!mOnGoingKeyCodes.contains((Object) keyCode)) {
			mOnGoingKeyCodes.add(keyCode);
		}
	}

	public void removeOngoingKeyCode(Integer keyCode) {
		mOnGoingKeyCodes.remove((Object) keyCode);
	}

	private int getActionIndex(final ActionType atype) {
		int index = mTapCount * 2;
		if (atype == ActionType.CLICK) {index++;}
		return index;
	}

	public boolean hasMoreAction(final ActionType atype, final boolean next) {
		boolean result = false;
		int index = getActionIndex(atype);
		if (next){ index++; }
		while (index < maxActions) {
			if (mActions[index] != null) {
				result = true;
				break;
			}
			index++;
		}
		return result;
	}

	public String getAction(final ActionType atype) {
		final int index = getActionIndex(atype);
		if (index >= mActions.length) { return null; }
		return mActions[index];
	}

	public Integer getTapTimeout() {
		return mTapTimeout;
	}
	
	public Integer getPressTimeout() {
		return mPressTimeout;
	}
	
	public Long getDownTime() {
		return mDownTime;
	}
	
	public Long getEventTime() {
		return mEventTime;
	}
	
	public State getState() {
		return mState;
	}
	
	public Integer getTapCount() {
		return mTapCount;
	}
	
	public String getCurrentApplication() {
		return mCurrentApplication;
	}
	
	public void cancelEvent() {
		cancelEvent(false);
	}

	public void cancelEvent(Boolean forcedReset) {
		synchronized (mLock) {
			if (mState == State.ONGOING || forcedReset) {
				mState = forcedReset ? State.PENDING : State.CANCELED;
			}
		}
	}
	
	public void invokeEvent() {
		synchronized (mLock) {
			if (mState == State.ONGOING) {
				mState = State.INVOKED;
			}
		}
	}

	public void setLongPress(LongPressType longPress) {
		synchronized (mLock) {
			mLongPress = longPress;
		}
	}

	public LongPressType getLongPress() {
		return mLongPress;
	}

	public Integer getLastQueuedKey() {
		return this.mLastQueuedKey;
	}
}
