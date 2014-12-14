package com.spazedog.xposed.additionsgb.backend.pwm;

import android.view.KeyEvent;

public class EventKey {
	public static enum EventKeyType { DEVICE, INVOKED }
	public static enum PressStates { NONE, DOWN, UP }

    private KeyEvent mKeyEvent = null; //Original KeyEvent, used as status
    private Integer mFlags;
	private Integer mRepeatCount;
    private PressStates mDevicePressState;
    private PressStates mSentPressState;

	private EventManager mManager;
	
	protected EventKey(EventManager manager) {
		mManager = manager;
	}
	
	protected void initiateInstance(KeyEvent keyEvent, Integer flags) {
        mKeyEvent = keyEvent;
		mFlags = flags;
        mRepeatCount = 0;
        mDevicePressState = PressStates.NONE;
        mSentPressState = PressStates.NONE;
	}
	
	protected void setKetPressDevice(Boolean pressed) {
		if (pressed) {
            mDevicePressState = PressStates.DOWN;
        } else {
            mDevicePressState = PressStates.UP;
        }
	}

    protected void setKeyPressSent(Boolean pressed) {
        if (pressed) {
            mSentPressState = PressStates.DOWN;
            mRepeatCount++;
        } else {
            mSentPressState = PressStates.UP;
        }
    }

    public KeyEvent getKeyEvent() { return mKeyEvent; }

	public Integer getCode() {
		return mKeyEvent == null ? 0 : mKeyEvent.getKeyCode();
	}

    public Boolean isPressed() {
        return (mDevicePressState == PressStates.DOWN);
    }

    public void setUnused(){
        mKeyEvent = null;
    }

    public Boolean isUsed() {
        return mKeyEvent != null;
    }

    public void invokeAndRelease(KeyEvent keyEvent) {
        if (mSentPressState == PressStates.NONE) {
            this.injectInputEvent(KeyEvent.ACTION_MULTIPLE, keyEvent);
            mSentPressState = PressStates.UP;
        } else if (mSentPressState == PressStates.DOWN) {
            release(keyEvent);
        }
    }

    //Also handle repeats
    public void invoke(KeyEvent keyEvent) {
        if (mSentPressState != PressStates.UP) {
            this.injectInputEvent(KeyEvent.ACTION_DOWN, keyEvent);
            mSentPressState = PressStates.DOWN;
            mRepeatCount += 1;
        }
    }

    public void release(KeyEvent keyEvent) {
        if (mSentPressState == PressStates.DOWN) {
            this.injectInputEvent(KeyEvent.ACTION_UP, keyEvent);
        }
        mSentPressState = PressStates.UP;
    }

    private void injectInputEvent(Integer action, KeyEvent keyEvent) {
        int repeatCount = (action == KeyEvent.ACTION_DOWN) ? mRepeatCount : 0;
        if (keyEvent == null) {
            keyEvent = mKeyEvent;
        }
        mManager.injectInputEvent(keyEvent, action, repeatCount, mFlags);
        //mManager.injectInputEvent(keyEvent, action, 0L, 0L, repeatCount, mFlags, 0);
    }
}
