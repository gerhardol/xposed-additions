package com.spazedog.xposed.additionsgb.backend.pwm;

import android.view.KeyEvent;

public class EventKey {
	
	public static final int FLAG_CUSTOM = 4096;
	
	private Long mDownTime;
	private Integer mKeyCode;
	public static enum PressStates { DOWN, UP }

    private KeyEvent mKeyEvent = null; //Original KeyEvent, used as status
    private Integer mFlags;
    private PressStates mDevicePressState;

    void initiateInstance(KeyEvent keyEvent, Integer flags) {
        mKeyEvent = new KeyEvent(keyEvent);
        mFlags = flags;
        mDevicePressState = PressStates.DOWN;
		
		/*
		 * This will allow us to distinguish between our injected keys and 
		 * others like from the software navigation bar. 
		 */
		if ((flags & FLAG_CUSTOM) == 0) {
			mFlags |= FLAG_CUSTOM;
	}
	}
	
	void setKetPressDevice(Boolean pressed) {
		if (pressed) {
            mDevicePressState = PressStates.DOWN;
        } else {
            mDevicePressState = PressStates.UP;
        }
	}

    public KeyEvent getKeyEvent() { return mKeyEvent; }

	public Integer getCode() {
		return mKeyEvent == null ? 0 : mKeyEvent.getKeyCode();
	}

    public Integer getFlags(){ return mFlags; }

    public Boolean isPressed() {
        return (mDevicePressState == PressStates.DOWN);
    }

    public void setUnused(){
        mKeyEvent = null;
    }

    public Boolean isUsed() {
        return mKeyEvent != null;
    }
}
