package com.spazedog.xposed.additionsgb.backend.pwm;

import android.view.KeyEvent;

public class EventKey {
	public static enum EventKeyType { DEVICE, INVOKED }
	
	private Long mDownTime;
	private Integer mKeyCode;
	private Integer mFlags;
	private Integer mMetaState;
	private Integer mRepeatCount;
	private Boolean mIsPressed;
	private Boolean mIsOnGoing;
	private EventKeyType mKeyType;
	
	private EventManager mManager;
	
	protected EventKey(EventManager manager) {
		mManager = manager;
	}
	
	protected void initiateInstance(Integer keyCode, Integer flags, Integer metaState, Long downTime) {
		mIsOnGoing = false;
		mRepeatCount = 0;
		mKeyCode = keyCode;
		mFlags = flags;
		mMetaState = metaState;
		mDownTime = downTime;
		mKeyType = EventKeyType.DEVICE;
	}
	
	protected void updateInstance(Boolean pressed, EventKeyType keyType) {
		mIsPressed = pressed;
		mKeyType = keyType;
	}
	
	//public Long getDownTime() {
	//	return mDownTime;
	//}

	//public Integer getPosition() {
	//	return mManager.getKeyCodePosition(mKeyCode);
	//}

	public Integer getCode() {
		return mKeyCode;
	}

	public Integer getFlags() {
		return mFlags;
	}
	
	//public Integer getMetaState() {
	//	return mMetaState;
	//}

	//public Integer getRepeatCount() {
	//	return mRepeatCount;
	//}

	public Boolean isPressed() {
		return mIsPressed;
	}

	public Boolean isLastQueued() {
		return (mKeyType == EventKeyType.DEVICE) && mManager.getLastQueuedKeyCode().equals(mKeyCode);
	}

	//public Boolean isOnGoing() {
	//	return mIsOnGoing;
	//}

	public void invokeAndRelease() {
		if (!mIsOnGoing) {
			for (EventKey combo: mManager.getKeys(mKeyType)) {

				if (!combo.mKeyCode.equals(mKeyCode) && !combo.mIsOnGoing) {
					combo.mIsOnGoing = true;
					combo.injectInputEvent(KeyEvent.ACTION_DOWN);
				}
			}
			
			this.injectInputEvent(KeyEvent.ACTION_MULTIPLE);
			
		} else {
			release();
		}
	}
	
	public void invoke() {
		if (mIsPressed) {
			if (!mIsOnGoing) {
				for (EventKey combo: mManager.getKeys(mKeyType)) {

					if (!combo.mKeyCode.equals(mKeyCode) && !combo.mIsOnGoing) {
						combo.mIsOnGoing = true;
						combo.injectInputEvent(KeyEvent.ACTION_DOWN);
					}
				}

				mIsOnGoing = true;
			}

			this.injectInputEvent(KeyEvent.ACTION_DOWN);
			mRepeatCount += 1;
		}
	}
	
	public void injectInputEvent(Integer action) {
		int repeatCount = (action == KeyEvent.ACTION_DOWN) ? mRepeatCount : 0;
		mManager.injectInputEvent(mKeyCode, action, 0L, 0L, repeatCount, mFlags, mMetaState);
	}
	
	public void release() {
		if (mIsOnGoing) {
			mIsOnGoing = false;
			this.injectInputEvent(KeyEvent.ACTION_UP);
		}
	}
}
