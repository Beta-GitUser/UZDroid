/*
** i_input.cpp
**
** Handles input from keyboard, mouse, and joystick
**
**---------------------------------------------------------------------------
**
** Copyright 2005-2016 Marisa Heit
** Copyright 2010-2016 Christoph Oelckers
** Copyright 2017-2025 GZDoom Maintainers and Contributors
** Copyright 2025-2026 UZDoom Maintainers and Contributors
**
** SPDX-License-Identifier: GPL-3.0-or-later
**
**---------------------------------------------------------------------------
**
** Code written prior to 2026 is also licensed under:
**
** SPDX-License-Identifier: BSD-3-Clause
**
**---------------------------------------------------------------------------
**
*/

#include "sdl_include.h"
#include "SDL_system.h"

#ifdef __ANDROID__
#include <jni.h>
#endif
#include <assert.h>
#include <math.h>
#include <algorithm>
#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "basics.h"
#include "c_buttons.h"
#include "c_cvars.h"
#include "c_bind.h"
#include "c_dispatch.h"
#include "d_eventbase.h"
#include "d_gui.h"
#include "dikeys.h"
#include "engineerrors.h"
#include "gamestate.h"
#include "i_input.h"
#include "i_interface.h"
#include "keydef.h"
#include "printf.h"
#include "m_haptics.h"
#include "m_joy.h"
#include "tarray.h"
#include "utf8.h"
#include "v_video.h"
#include "version.h"

bool GUICapture;
static bool NativeMouse = true;

CVAR (Bool, use_mouse, true, CVAR_ARCHIVE|CVAR_GLOBALCONFIG)
CUSTOM_CVARD (Int, joykey_stop_conflict, -1, CVAR_ARCHIVE|CVAR_GLOBALCONFIG,
	"Detect joypad/keyboard conflicts, dropping events as needed. "
	"Useful for handheld PCs such as the SteamDeck. "
	"-1: auto-detect, 0: disabled, 1: detected, 2: forced"
)
{
	if (self < -1) self = -1;
	else if (self > 2) self = 2;
}

extern int WaitingForKey;

static const SDL_Keycode DIKToKeySym[256] =
{
	SDLK_UNKNOWN,  SDLK_ESCAPE,       SDLK_1,            SDLK_2,
	SDLK_3,        SDLK_4,            SDLK_5,            SDLK_6,
	SDLK_7,        SDLK_8,            SDLK_9,            SDLK_0,
	SDLK_MINUS,    SDLK_EQUALS,       SDLK_BACKSPACE,    SDLK_TAB,
	SDLK_q,        SDLK_w,            SDLK_e,            SDLK_r,
	SDLK_t,        SDLK_y,            SDLK_u,            SDLK_i,
	SDLK_o,        SDLK_p,            SDLK_LEFTBRACKET,  SDLK_RIGHTBRACKET,
	SDLK_RETURN,   SDLK_LCTRL,        SDLK_a,            SDLK_s,
	SDLK_d,        SDLK_f,            SDLK_g,            SDLK_h,
	SDLK_j,        SDLK_k,            SDLK_l,            SDLK_SEMICOLON,
	SDLK_QUOTE,    SDLK_BACKQUOTE,    SDLK_LSHIFT,       SDLK_BACKSLASH,
	SDLK_z,        SDLK_x,            SDLK_c,            SDLK_v,
	SDLK_b,        SDLK_n,            SDLK_m,            SDLK_COMMA,
	SDLK_PERIOD,   SDLK_SLASH,        SDLK_RSHIFT,       SDLK_KP_MULTIPLY,
	SDLK_LALT,     SDLK_SPACE,        SDLK_CAPSLOCK,     SDLK_F1,
	SDLK_F2,       SDLK_F3,           SDLK_F4,           SDLK_F5,
	SDLK_F6,       SDLK_F7,           SDLK_F8,           SDLK_F9,
	SDLK_F10,      SDLK_NUMLOCKCLEAR, SDLK_SCROLLLOCK,   SDLK_KP_7,
	SDLK_KP_8,     SDLK_KP_9,         SDLK_KP_MINUS,     SDLK_KP_4,
	SDLK_KP_5,     SDLK_KP_6,         SDLK_KP_PLUS,      SDLK_KP_1,
	SDLK_KP_2,     SDLK_KP_3,         SDLK_KP_0,         SDLK_KP_PERIOD,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_F11,
	SDLK_F12,      SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_F13,      SDLK_F14,          SDLK_F15,          SDLK_F16,
	SDLK_F17,      SDLK_F18,          SDLK_F19,          SDLK_F20,
	SDLK_F21,      SDLK_F22,          SDLK_F23,          SDLK_F24,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_KP_EQUALS,    SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_AT,           SDLK_COLON,        SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_KP_ENTER, SDLK_RCTRL,        SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_KP_COMMA,
	SDLK_UNKNOWN,  SDLK_KP_DIVIDE,    SDLK_UNKNOWN,      SDLK_SYSREQ,
	SDLK_RALT,     SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_PAUSE,        SDLK_UNKNOWN,      SDLK_HOME,
	SDLK_UP,       SDLK_PAGEUP,       SDLK_UNKNOWN,      SDLK_LEFT,
	SDLK_UNKNOWN,  SDLK_RIGHT,        SDLK_UNKNOWN,      SDLK_END,
	SDLK_DOWN,     SDLK_PAGEDOWN,     SDLK_INSERT,       SDLK_DELETE,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_LGUI,
	SDLK_RGUI,     SDLK_MENU,         SDLK_POWER,        SDLK_SLEEP,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_AC_SEARCH,    SDLK_AC_BOOKMARKS, SDLK_AC_REFRESH,
	SDLK_AC_STOP,  SDLK_AC_FORWARD,   SDLK_AC_BACK,      SDLK_COMPUTER,
	SDLK_MAIL,     SDLK_MEDIASELECT,  SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN,
	SDLK_UNKNOWN,  SDLK_UNKNOWN,      SDLK_UNKNOWN,      SDLK_UNKNOWN
};

static const SDL_Scancode DIKToKeyScan[256] =
{
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_ESCAPE,       SDL_SCANCODE_1,            SDL_SCANCODE_2,
	SDL_SCANCODE_3,          SDL_SCANCODE_4,            SDL_SCANCODE_5,            SDL_SCANCODE_6,
	SDL_SCANCODE_7,          SDL_SCANCODE_8,            SDL_SCANCODE_9,            SDL_SCANCODE_0,
	SDL_SCANCODE_MINUS,      SDL_SCANCODE_EQUALS,       SDL_SCANCODE_BACKSPACE,    SDL_SCANCODE_TAB,
	SDL_SCANCODE_Q,          SDL_SCANCODE_W,            SDL_SCANCODE_E,            SDL_SCANCODE_R,
	SDL_SCANCODE_T,          SDL_SCANCODE_Y,            SDL_SCANCODE_U,            SDL_SCANCODE_I,
	SDL_SCANCODE_O,          SDL_SCANCODE_P,            SDL_SCANCODE_LEFTBRACKET,  SDL_SCANCODE_RIGHTBRACKET,
	SDL_SCANCODE_RETURN,     SDL_SCANCODE_LCTRL,        SDL_SCANCODE_A,            SDL_SCANCODE_S,
	SDL_SCANCODE_D,          SDL_SCANCODE_F,            SDL_SCANCODE_G,            SDL_SCANCODE_H,
	SDL_SCANCODE_J,          SDL_SCANCODE_K,            SDL_SCANCODE_L,            SDL_SCANCODE_SEMICOLON,
	SDL_SCANCODE_APOSTROPHE, SDL_SCANCODE_GRAVE,        SDL_SCANCODE_LSHIFT,       SDL_SCANCODE_BACKSLASH,
	SDL_SCANCODE_Z,          SDL_SCANCODE_X,            SDL_SCANCODE_C,            SDL_SCANCODE_V,
	SDL_SCANCODE_B,          SDL_SCANCODE_N,            SDL_SCANCODE_M,            SDL_SCANCODE_COMMA,
	SDL_SCANCODE_PERIOD,     SDL_SCANCODE_SLASH,        SDL_SCANCODE_RSHIFT,       SDL_SCANCODE_KP_MULTIPLY,
	SDL_SCANCODE_LALT,       SDL_SCANCODE_SPACE,        SDL_SCANCODE_CAPSLOCK,     SDL_SCANCODE_F1,
	SDL_SCANCODE_F2,         SDL_SCANCODE_F3,           SDL_SCANCODE_F4,           SDL_SCANCODE_F5,
	SDL_SCANCODE_F6,         SDL_SCANCODE_F7,           SDL_SCANCODE_F8,           SDL_SCANCODE_F9,
	SDL_SCANCODE_F10,        SDL_SCANCODE_NUMLOCKCLEAR, SDL_SCANCODE_SCROLLLOCK,   SDL_SCANCODE_KP_7,
	SDL_SCANCODE_KP_8,       SDL_SCANCODE_KP_9,         SDL_SCANCODE_KP_MINUS,     SDL_SCANCODE_KP_4,
	SDL_SCANCODE_KP_5,       SDL_SCANCODE_KP_6,         SDL_SCANCODE_KP_PLUS,      SDL_SCANCODE_KP_1,
	SDL_SCANCODE_KP_2,       SDL_SCANCODE_KP_3,         SDL_SCANCODE_KP_0,         SDL_SCANCODE_KP_PERIOD,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_F11,
	SDL_SCANCODE_F12,        SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_F13,        SDL_SCANCODE_F14,          SDL_SCANCODE_F15,          SDL_SCANCODE_F16,
	SDL_SCANCODE_F17,        SDL_SCANCODE_F18,          SDL_SCANCODE_F19,          SDL_SCANCODE_F20,
	SDL_SCANCODE_F21,        SDL_SCANCODE_F22,          SDL_SCANCODE_F23,          SDL_SCANCODE_F24,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_KP_EQUALS,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_KP_ENTER,   SDL_SCANCODE_RCTRL,        SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_KP_COMMA,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_KP_DIVIDE,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_SYSREQ,
	SDL_SCANCODE_RALT,       SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_PAUSE,        SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_HOME,
	SDL_SCANCODE_UP,         SDL_SCANCODE_PAGEUP,       SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_LEFT,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_RIGHT,        SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_END,
	SDL_SCANCODE_DOWN,       SDL_SCANCODE_PAGEDOWN,     SDL_SCANCODE_INSERT,       SDL_SCANCODE_DELETE,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_LGUI,
	SDL_SCANCODE_RGUI,       SDL_SCANCODE_MENU,         SDL_SCANCODE_POWER,        SDL_SCANCODE_SLEEP,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_AC_SEARCH,    SDL_SCANCODE_AC_BOOKMARKS, SDL_SCANCODE_AC_REFRESH,
	SDL_SCANCODE_AC_STOP,    SDL_SCANCODE_AC_FORWARD,   SDL_SCANCODE_AC_BACK,      SDL_SCANCODE_COMPUTER,
	SDL_SCANCODE_MAIL,       SDL_SCANCODE_MEDIASELECT,  SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,
	SDL_SCANCODE_UNKNOWN,    SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN,      SDL_SCANCODE_UNKNOWN
};

static TMap<SDL_Keycode, uint8_t> InitKeySymMap ()
{
	TMap<SDL_Keycode, uint8_t> KeySymToDIK;

	for (int i = 0; i < 256; ++i)
	{
		KeySymToDIK[DIKToKeySym[i]] = i;
	}
	KeySymToDIK[0] = 0;
	KeySymToDIK[SDLK_RSHIFT] = DIK_LSHIFT;
	KeySymToDIK[SDLK_RCTRL] = DIK_LCONTROL;
	KeySymToDIK[SDLK_RALT] = DIK_LMENU;
	// Depending on your Linux flavor, you may get SDLK_PRINT or SDLK_SYSREQ
	KeySymToDIK[SDLK_PRINTSCREEN] = DIK_SYSRQ;

	return KeySymToDIK;
}
static const TMap<SDL_Keycode, uint8_t> KeySymToDIK(InitKeySymMap());

static TMap<SDL_Scancode, uint8_t> InitKeyScanMap ()
{
	TMap<SDL_Scancode, uint8_t> KeyScanToDIK;

	for (int i = 0; i < 256; ++i)
	{
		KeyScanToDIK[DIKToKeyScan[i]] = i;
	}

	return KeyScanToDIK;
}
static const TMap<SDL_Scancode, uint8_t> KeyScanToDIK(InitKeyScanMap());

static void I_CheckGUICapture ()
{
	bool wantCapt = sysCallbacks.WantGuiCapture && sysCallbacks.WantGuiCapture();

	if (wantCapt != GUICapture)
	{
		GUICapture = wantCapt;
		if (wantCapt)
		{
			buttonMap.ResetButtonStates();
		}
	}
}

void I_SetMouseCapture()
{
	// Clear out any mouse movement.
	SDL_GetRelativeMouseState (NULL, NULL);
	SDL_CaptureMouse (SDL_TRUE);
}

void I_ReleaseMouseCapture()
{
	SDL_CaptureMouse (SDL_FALSE);
}

static void MouseRead ()
{
	int x, y;

	if (NativeMouse)
	{
		return;
	}

	SDL_GetRelativeMouseState (&x, &y);

	if (joykey_stop_conflict > 0) return;

	PostMouseMove (x, y);
}

static void I_CheckNativeMouse ()
{
	bool focus = SDL_GetKeyboardFocus() != NULL;

	bool captureModeInGame = sysCallbacks.CaptureModeInGame && sysCallbacks.CaptureModeInGame();
	bool wantNative = !focus || (!use_mouse || GUICapture || !captureModeInGame);

	if (!wantNative && sysCallbacks.WantNativeMouse && sysCallbacks.WantNativeMouse() && joykey_stop_conflict <= 0)
		wantNative = true;

	if (wantNative != NativeMouse)
	{
		NativeMouse = wantNative;
		SDL_ShowCursor (wantNative);
		if (wantNative) {
			SDL_SetRelativeMouseMode (SDL_FALSE);
		} else {
			SDL_GetRelativeMouseState (NULL, NULL);
			SDL_SetRelativeMouseMode (SDL_TRUE);
		}
	}
}


//===========================================================================
//===========================================================================
//
// Touch input
//
// Gameplay has two raw touch zones: the left strip moves (a virtual stick that
// presses the movement keys) and the rest looks (a trackpad feeding the mouse
// path, so the sensitivity and invert cvars apply). Menus get a trackpad as
// well, moving a pointer that a tap clicks.
//
// Fire, use, jump and everything else are on-screen buttons defined in
// touch-buttons.txt in the data folder. They reach the engine as ordinary key
// and mouse button events, so they stay bindable in Controls and need no code
// here.
//
//===========================================================================

#ifdef __ANDROID__
#define TOUCH_ENABLED_DEFAULT true
#else
#define TOUCH_ENABLED_DEFAULT false
#endif

CUSTOM_CVARD (Bool, touch_enable, TOUCH_ENABLED_DEFAULT, CVAR_ARCHIVE | CVAR_GLOBALCONFIG,
	"Touch controls: trackpad look, virtual stick to move, buttons for the rest")
{
	// SDL would otherwise turn every touch into a synthetic mouse first, and
	// then every touch would arrive twice.
	SDL_SetHint(SDL_HINT_TOUCH_MOUSE_EVENTS, *touch_enable ? "0" : "1");
}

// The mouse sensitivity cvars are applied by PostMouseMove() on top of this.
CVAR (Float, touch_sensitivity, 1.0f, CVAR_ARCHIVE | CVAR_GLOBALCONFIG)
// Width of the movement strip, as a fraction of the window width.
CVAR (Float, touch_movezone, 0.35f, CVAR_ARCHIVE | CVAR_GLOBALCONFIG)
// State, not a setting: the Java side keeps it in step with the input devices,
// because Android already draws a pointer for a connected mouse.
CVAR (Bool, touch_mouse_present, false, 0)

// How fast the menu pointer follows a drag. Separate from the look
// sensitivity: a menu is dragged pixel by pixel, aiming is not.
CVAR (Float, touch_cursor_speed, 2.0f, CVAR_ARCHIVE | CVAR_GLOBALCONFIG)
// How far the stick must leave its origin before it moves, as a fraction of
// its radius.
CVAR (Float, touch_deadzone, 0.2f, CVAR_ARCHIVE | CVAR_GLOBALCONFIG)

namespace
{
	// The stick's radius, as a fraction of the shorter screen edge.
	constexpr float StickRadiusFraction = 0.10f;
	// A tap neither travels far nor stays down long.
	constexpr float TapTravelFraction = 0.03f;
	constexpr Uint32 TapMaxDuration = 250;

	// Budgeted so a long session cannot drown the console, but the first few
	// gestures of a run always say what the engine decided.
	void TouchLog (const char *what, int a = 0, int b = 0)
	{
		static int budget = 60;

		if (budget <= 0)
			return;
		budget--;
		Printf("IN touch %s (%d, %d)\n", what, a, b);
	}

	template<typename T> T TouchClamp (T value, T low, T high)
	{
		return value < low ? low : (value > high ? high : value);
	}

	struct FTrackedFinger
	{
		SDL_FingerID Id = 0;
		bool Down = false;
		float StartX = 0, StartY = 0;
		float LastX = 0, LastY = 0;
		float Travel = 0;
		Uint32 DownTime = 0;
	};

	FTrackedFinger LookFinger;	// gameplay look
	FTrackedFinger MoveFinger;	// gameplay stick
	FTrackedFinger TapFinger;	// the only finger that can become a menu click

	// Fractional remainders, so slow drags are not lost to integer truncation.
	float LookRemX = 0, LookRemY = 0;

	int TouchWindowW = 0, TouchWindowH = 0;

	// The menu pointer, in window pixels.
	float CursorX = 0, CursorY = 0;
	bool CursorPlaced = false;

	// Which way the stick currently pushes (-1, 0, 1 on each axis).
	int StickKeysX = 0, StickKeysY = 0;

	bool TouchUiContext ()
	{
		return GUICapture || gamestate != GS_LEVEL;
	}

	void TouchWindowBounds (const SDL_TouchFingerEvent &tf)
	{
		TouchWindowW = TouchWindowH = 0;

		if (SDL_Window *window = SDL_GetWindowFromID(tf.windowID))
		{
			SDL_GetWindowSize(window, &TouchWindowW, &TouchWindowH);
		}
		if (TouchWindowW <= 0 || TouchWindowH <= 0)
		{
			TouchWindowW = 1280;
			TouchWindowH = 720;
		}
	}

	int TouchKeyMods ()
	{
		SDL_Keymod kmod = SDL_GetModState();

		return ((kmod & KMOD_SHIFT) ? GKM_SHIFT : 0) |
		       ((kmod & KMOD_CTRL) ? GKM_CTRL : 0) |
		       ((kmod & KMOD_ALT) ? GKM_ALT : 0);
	}

	void TouchPostKey (int key, bool down)
	{
		event_t event = { 0,0,0,0,0,0,0 };

		event.type = down ? EV_KeyDown : EV_KeyUp;
		event.data1 = key;
		D_PostEvent(&event);
	}

	void TouchPostGui (EGUIEvent subtype, int x, int y)
	{
		if (screen == nullptr)
			return;

		event_t event = { 0,0,0,0,0,0,0 };

		event.type = EV_GUI_Event;
		event.data1 = x;
		event.data2 = y;
		event.data3 = TouchKeyMods();
		screen->ScaleCoordsFromWindow(event.data1, event.data2);
		event.subtype = subtype;
		D_PostEvent(&event);
	}



	//--- on screen buttons ----------------------------------------------------
	//
	// The buttons live here rather than in the overlay because Android gives a
	// view that owns the first finger the whole gesture: a held button would
	// starve every other finger, and looking around while firing is the point.
	// Here every finger is just a finger, and the button is a rectangle.

	struct FTouchButton
	{
		float x = 0, y = 0, w = 0, h = 0;	// fractions of the window
		int key = -1;						// GZDoom key code, or
		int mouse = 0;						// 1..3, or
		FString command;					// a console command
		bool hold = false;					// run the opposite on release
		SDL_FingerID finger = 0;
		bool down = false;
	};

	TArray<FTouchButton> TouchButtons;

	// Editing: drag a button to where it belongs, then leave the mode and the
	// overlay writes the file back.
	bool TouchEditing = false;
	int TouchEditIndex = -1;
	float TouchEditGrabX = 0, TouchEditGrabY = 0;
	SDL_FingerID TouchEditFinger = 0;

	int TouchKeyFromName (const char *name)
	{
		// The same names the bind command takes, plus a "#123" escape hatch.
		if (name[0] == '#' && name[1] != 0)
			return atoi(name + 1);

		for (int i = 0; i < NUM_KEYS; i++)
		{
			if (KeyNames[i] != nullptr && !stricmp(KeyNames[i], name))
				return i;
		}
		return -1;
	}

	void TouchPushButtonState (int index, bool down)
	{
#ifdef __ANDROID__
		static jmethodID method = nullptr;

		JNIEnv *env = static_cast<JNIEnv *>(SDL_AndroidGetJNIEnv());
		jobject activity = static_cast<jobject>(SDL_AndroidGetActivity());

		if (env == nullptr || activity == nullptr)
			return;

		jclass cls = env->GetObjectClass(activity);

		if (method == nullptr)
		{
			method = env->GetMethodID(cls, "onTouchButtonPressed", "(IZ)V");
		}

		if (method != nullptr)
		{
			env->CallVoidMethod(activity, method, index, down ? JNI_TRUE : JNI_FALSE);
		}

		if (env->ExceptionCheck())
		{
			env->ExceptionClear();
		}

		env->DeleteLocalRef(cls);
		env->DeleteLocalRef(activity);
#else
		(void)index; (void)down;
#endif
	}

	void TouchPushEditMode (bool editing)
	{
#ifdef __ANDROID__
		JNIEnv *env = static_cast<JNIEnv *>(SDL_AndroidGetJNIEnv());
		jobject activity = static_cast<jobject>(SDL_AndroidGetActivity());

		if (env == nullptr || activity == nullptr)
			return;

		jclass cls = env->GetObjectClass(activity);
		jmethodID method = env->GetMethodID(cls, "onTouchEditMode", "(Z)V");

		if (method != nullptr)
		{
			env->CallVoidMethod(activity, method, editing ? JNI_TRUE : JNI_FALSE);
		}
		if (env->ExceptionCheck())
		{
			env->ExceptionClear();
		}

		env->DeleteLocalRef(cls);
		env->DeleteLocalRef(activity);
#else
		(void)editing;
#endif
	}

	void TouchPushButtonMoved (int index, float x, float y)
	{
#ifdef __ANDROID__
		JNIEnv *env = static_cast<JNIEnv *>(SDL_AndroidGetJNIEnv());
		jobject activity = static_cast<jobject>(SDL_AndroidGetActivity());

		if (env == nullptr || activity == nullptr)
			return;

		jclass cls = env->GetObjectClass(activity);
		jmethodID method = env->GetMethodID(cls, "onTouchButtonMoved", "(IFF)V");

		if (method != nullptr)
		{
			env->CallVoidMethod(activity, method, index, x, y);
		}
		if (env->ExceptionCheck())
		{
			env->ExceptionClear();
		}

		env->DeleteLocalRef(cls);
		env->DeleteLocalRef(activity);
#else
		(void)index; (void)x; (void)y;
#endif
	}

	FString TouchOpposite (const FString &command)
	{
		// "+attack" and "-attack" are two halves of one button.
		if (command.Len() > 0 && (command[0] == '+' || command[0] == '-'))
		{
			FString flipped;
			flipped.Format("%c%s", (command[0] == '+') ? '-' : '+', command.GetChars() + 1);
			return flipped;
		}
		return FString();
	}

	void TouchButtonSend (unsigned index, bool down)
	{
		FTouchButton &button = TouchButtons[index];

		if (button.down == down)
			return;

		button.down = down;

		if (button.command.IsNotEmpty())
		{
			if (down)
			{
				AddCommandString(button.command);
			}
			else if (button.hold)
			{
				FString opposite = TouchOpposite(button.command);
				if (opposite.IsNotEmpty())
				{
					AddCommandString(opposite);
				}
			}
		}
		else if (button.mouse > 0)
		{
			TouchPostKey(KEY_MOUSE1 + button.mouse - 1, down);
		}
		else if (button.key >= 0)
		{
			TouchPostKey(button.key, down);
		}

		TouchPushButtonState((int)index, down);
	}

	int TouchButtonAt (float fx, float fy)
	{
		for (unsigned i = 0; i < TouchButtons.Size(); i++)
		{
			const FTouchButton &button = TouchButtons[i];

			if (fx >= button.x && fx <= button.x + button.w &&
				fy >= button.y && fy <= button.y + button.h)
			{
				return (int)i;
			}
		}
		return -1;
	}

	bool TouchButtonsRelease (SDL_FingerID finger)
	{
		bool owned = false;

		for (unsigned i = 0; i < TouchButtons.Size(); i++)
		{
			if (TouchButtons[i].down && TouchButtons[i].finger == finger)
			{
				TouchButtonSend(i, false);
				owned = true;
			}
		}
		return owned;
	}

	void TouchButtonsReleaseAll ()
	{
		for (unsigned i = 0; i < TouchButtons.Size(); i++)
		{
			if (TouchButtons[i].down)
			{
				TouchButtonSend(i, false);
			}
		}
	}

	//--- the menu pointer -----------------------------------------------------

	void TouchCursorMove (float dx, float dy)
	{
		if (!CursorPlaced)
		{
			CursorX = TouchWindowW * 0.5f;
			CursorY = TouchWindowH * 0.5f;
			CursorPlaced = true;
		}

		CursorX = TouchClamp(CursorX + dx, 0.f, float(TouchWindowW - 1));
		CursorY = TouchClamp(CursorY + dy, 0.f, float(TouchWindowH - 1));

		TouchPointerX = int(CursorX);
		TouchPointerY = int(CursorY);
		TouchPointerPlaced = true;

		TouchPostGui(EV_GUI_MouseMove, int(CursorX), int(CursorY));
	}

	void TouchCursorClick ()
	{
		if (!CursorPlaced)
		{
			TouchCursorMove(0, 0);
		}

		TouchPostGui(EV_GUI_LButtonDown, int(CursorX), int(CursorY));
		TouchPostGui(EV_GUI_LButtonUp, int(CursorX), int(CursorY));
	}

	//--- the movement stick ---------------------------------------------------

	float TouchStickRadius ()
	{
		return std::max(1.f, std::min(TouchWindowW, TouchWindowH) * StickRadiusFraction);
	}

	// The stick drives the engine's own movement actions instead of pressing
	// keys. Keys would make it depend on whatever happens to be bound, and the
	// defaults turn rather than strafe; +forward/+moveright are the actions
	// themselves, so they work with any bindings and with mods that alias them.
	void TouchStickPush (int x, int y)
	{
		if (x != StickKeysX)
		{
			if (StickKeysX < 0)		AddCommandString("-moveleft");
			if (StickKeysX > 0)		AddCommandString("-moveright");
			if (x < 0)				AddCommandString("+moveleft");
			if (x > 0)				AddCommandString("+moveright");
			StickKeysX = x;
		}

		if (y != StickKeysY)
		{
			if (StickKeysY < 0)		AddCommandString("-back");
			if (StickKeysY > 0)		AddCommandString("-forward");
			if (y < 0)				AddCommandString("+back");
			if (y > 0)				AddCommandString("+forward");
			StickKeysY = y;
		}
	}

	// Hands the knob position to the Java overlay, which draws JoyFrame and
	// JoyKnob. Silent when there is nothing to draw on.
	void TouchStickVisuals ()
	{
#ifdef __ANDROID__
		static jmethodID method = nullptr;

		JNIEnv *env = static_cast<JNIEnv *>(SDL_AndroidGetJNIEnv());
		jobject activity = static_cast<jobject>(SDL_AndroidGetActivity());

		if (env == nullptr || activity == nullptr)
			return;

		jclass cls = env->GetObjectClass(activity);

		if (method == nullptr)
		{
			method = env->GetMethodID(cls, "onTouchStick", "(FFFFZ)V");
		}

		if (method != nullptr)
		{
			const float radius = TouchStickRadius();
			const float dx = MoveFinger.Down ? TouchClamp((MoveFinger.LastX - MoveFinger.StartX) / radius, -1.f, 1.f) : 0.f;
			const float dy = MoveFinger.Down ? TouchClamp((MoveFinger.LastY - MoveFinger.StartY) / radius, -1.f, 1.f) : 0.f;

			env->CallVoidMethod(activity, method, MoveFinger.StartX, MoveFinger.StartY, dx, dy,
				MoveFinger.Down ? JNI_TRUE : JNI_FALSE);
		}

		if (env->ExceptionCheck())
		{
			env->ExceptionClear();
		}

		env->DeleteLocalRef(cls);
		env->DeleteLocalRef(activity);
#endif
	}

	void TouchStickRelease ()
	{
		TouchStickPush(0, 0);
		TouchStickVisuals();
	}

	void TouchStickUpdate ()
	{
		const float radius = TouchStickRadius();
		const float dead = radius * TouchClamp(float(touch_deadzone), 0.f, 0.9f);

		const float dx = MoveFinger.LastX - MoveFinger.StartX;
		const float dy = MoveFinger.LastY - MoveFinger.StartY;

		// Diagonals are part of the deal: the dead zone applies per axis.
		int x = 0, y = 0;

		if (fabsf(dx) > dead)	x = (dx < 0) ? -1 : 1;
		if (fabsf(dy) > dead)	y = (dy < 0) ? 1 : -1;	// up the screen is forward

		TouchStickPush(x, y);
		TouchStickVisuals();
	}

	//--- finger bookkeeping ---------------------------------------------------

	void TouchForget (FTrackedFinger &finger)
	{
		finger.Down = false;
		finger.Id = 0;
	}

	void TouchReset ()
	{
		TouchButtonsReleaseAll();
		TouchStickRelease();
		TouchForget(LookFinger);
		TouchForget(MoveFinger);
		TouchForget(TapFinger);
		LookRemX = LookRemY = 0;
	}

	// Keeping the two contexts apart matters more than any single event: when
	// the menu opens mid-drag the stick must not stay pressed.
	bool TouchLastUiContext;
	bool TouchContextKnown = false;

	void TouchPushContext (bool ui)
	{
#ifdef __ANDROID__
		static jmethodID method = nullptr;

		JNIEnv *env = static_cast<JNIEnv *>(SDL_AndroidGetJNIEnv());
		jobject activity = static_cast<jobject>(SDL_AndroidGetActivity());

		if (env == nullptr || activity == nullptr)
			return;

		jclass cls = env->GetObjectClass(activity);

		if (method == nullptr)
		{
			method = env->GetMethodID(cls, "onTouchContext", "(Z)V");
		}

		if (method != nullptr)
		{
			env->CallVoidMethod(activity, method, ui ? JNI_TRUE : JNI_FALSE);
		}

		if (env->ExceptionCheck())
		{
			env->ExceptionClear();
		}

		env->DeleteLocalRef(cls);
		env->DeleteLocalRef(activity);
#endif
	}

	void TouchUpdateContext ()
	{
		const bool ui = TouchUiContext();

		if (!TouchContextKnown || ui != TouchLastUiContext)
		{
			TouchContextKnown = true;
			TouchLastUiContext = ui;
			TouchReset();

			// A menu is not a gamepad: the overlay hides while the pointer is
			// in charge, so a stray thumb cannot fire or move while browsing.
			TouchPushContext(ui);
		}
	}

	void TouchFingerDown (const SDL_TouchFingerEvent &tf)
	{
		if (!touch_enable)
			return;

		TouchUpdateContext();
		TouchWindowBounds(tf);

		const float x = tf.x * TouchWindowW;
		const float y = tf.y * TouchWindowH;

		// Editing takes the fingers first: a drag moves the button under it
		// instead of firing it, and nothing looks around meanwhile.
		if (TouchEditing)
		{
			if (TouchEditIndex < 0)
			{
				const int index = TouchButtonAt(tf.x, tf.y);

				if (index >= 0)
				{
					TouchEditIndex = index;
					TouchEditFinger = tf.fingerId;
					TouchEditGrabX = tf.x - TouchButtons[index].x;
					TouchEditGrabY = tf.y - TouchButtons[index].y;
				}
			}
			return;
		}

		// A thumb on a button does not stop the other thumb from looking: the
		// button is a rectangle here, not a view that owns the gesture.
		if (!TouchUiContext())
		{
			const int index = TouchButtonAt(tf.x, tf.y);

			if (index >= 0 && !TouchButtons[index].down)
			{
				TouchButtons[index].finger = tf.fingerId;
				TouchButtonSend(index, true);
				return;
			}
		}

		FTrackedFinger *slot = nullptr;

		if (TouchUiContext())
		{
			if (!TapFinger.Down)
				slot = &TapFinger;
		}
		else if (x < TouchWindowW * TouchClamp(float(touch_movezone), 0.05f, 0.95f))
		{
			if (!MoveFinger.Down)
				slot = &MoveFinger;
		}
		else if (!LookFinger.Down)
		{
			slot = &LookFinger;
		}

		if (slot == nullptr)
			return;		// extra fingers are ignored, buttons are separate views

		slot->Down = true;
		slot->Id = tf.fingerId;
		slot->StartX = slot->LastX = x;
		slot->StartY = slot->LastY = y;
		slot->Travel = 0;
		slot->DownTime = SDL_GetTicks();

		if (slot == &MoveFinger)
		{
			TouchStickUpdate();
			TouchLog("stick down", int(x), int(y));
		}
		else
		{
			TouchLog(slot == &LookFinger ? "look down" : "pointer down", int(x), int(y));
		}
	}

	void TouchFingerMotion (const SDL_TouchFingerEvent &tf)
	{
		if (!touch_enable)
			return;

		TouchUpdateContext();
		TouchWindowBounds(tf);

		const float x = tf.x * TouchWindowW;
		const float y = tf.y * TouchWindowH;

		if (TouchEditing)
		{
			if (TouchEditIndex >= 0 && TouchEditFinger == tf.fingerId)
			{
				FTouchButton &button = TouchButtons[TouchEditIndex];

				button.x = TouchClamp(tf.x - TouchEditGrabX, 0.f, 1.f - button.w);
				button.y = TouchClamp(tf.y - TouchEditGrabY, 0.f, 1.f - button.h);

				TouchPushButtonMoved(TouchEditIndex, button.x, button.y);
			}
			return;
		}

		FTrackedFinger *finger = nullptr;

		if (LookFinger.Down && LookFinger.Id == tf.fingerId)		finger = &LookFinger;
		else if (MoveFinger.Down && MoveFinger.Id == tf.fingerId)	finger = &MoveFinger;
		else if (TapFinger.Down && TapFinger.Id == tf.fingerId)	finger = &TapFinger;
		else return;

		const float dx = x - finger->LastX;
		const float dy = y - finger->LastY;

		finger->Travel += fabsf(dx) + fabsf(dy);
		finger->LastX = x;
		finger->LastY = y;

		if (finger == &MoveFinger)
		{
			TouchStickUpdate();
		}
		else if (finger == &LookFinger)
		{
			// Trackpad: the delta is what the engine sees, so m_sensitivity_x/y
			// and the invert cvars do the rest. Remainders carry over, or slow
			// drags would round away to nothing.
			LookRemX += dx * touch_sensitivity;
			LookRemY += dy * touch_sensitivity;

			const int mx = int(LookRemX);
			const int my = int(LookRemY);

			LookRemX -= mx;
			LookRemY -= my;

			if (mx != 0 || my != 0)
			{
				PostMouseMove(mx, my);
			}
		}
		else
		{
			// Menu trackpad: move the pointer relative to where it already is.
			TouchCursorMove(dx * touch_cursor_speed, dy * touch_cursor_speed);
		}
	}

	void TouchFingerUp (const SDL_TouchFingerEvent &tf)
	{
		if (!touch_enable)
			return;

		if (TouchEditing)
		{
			if (TouchEditIndex >= 0 && TouchEditFinger == tf.fingerId)
			{
				TouchEditIndex = -1;
			}
			return;
		}

		if (TouchButtonsRelease(tf.fingerId))
			return;

		if (MoveFinger.Down && MoveFinger.Id == tf.fingerId)
		{
			TouchStickRelease();
			TouchForget(MoveFinger);
			return;
		}

		if (LookFinger.Down && LookFinger.Id == tf.fingerId)
		{
			TouchForget(LookFinger);
			return;
		}

		if (!TapFinger.Down || TapFinger.Id != tf.fingerId)
			return;

		// A short, straight touch with the finger is a click where the pointer
		// already points - not where the finger is, which is the point of a
		// trackpad.
		const float limit = std::min(TouchWindowW, TouchWindowH) * TapTravelFraction;
		const bool tap = TapFinger.Travel <= limit &&
			SDL_GetTicks() - TapFinger.DownTime <= TapMaxDuration;

		TouchForget(TapFinger);

		if (tap)
		{
			TouchLog("tap");
			TouchCursorClick();
		}
	}

	// The screen was locked, the app went away, something else took focus: no
	// key may stay down.
	void TouchSuspend ()
	{
		TouchReset();
	}
}

//===========================================================================
//
// Java -> console channel
//
// An on-screen button can run a console command instead of faking a key press.
// That is the same path a bound key takes, so it reaches aliases, mod actions
// and anything else the console can name - which matters once a mod pack brings
// more actions than a keyboard has keys.
//
// The command is carried on an SDL event, because the Java UI does not run on
// the thread that pumps events.
//
//===========================================================================

namespace
{
	Uint32 CommandEventType = (Uint32)-1;

	void EnsureCommandEvent ()
	{
		if (CommandEventType == (Uint32)-1)
		{
			CommandEventType = SDL_RegisterEvents(1);
		}
	}
}

// Called once when the engine starts pumping events: the overlay may have
// pushed the button table before the native side was ready.
void UZDroid_TouchReady ()
{
#ifdef __ANDROID__
	JNIEnv *env = static_cast<JNIEnv *>(SDL_AndroidGetJNIEnv());
	jobject activity = static_cast<jobject>(SDL_AndroidGetActivity());

	if (env == nullptr || activity == nullptr)
		return;

	jclass cls = env->GetObjectClass(activity);
	jmethodID method = env->GetMethodID(cls, "onTouchReady", "()V");

	if (method != nullptr)
	{
		env->CallVoidMethod(activity, method);
	}
	if (env->ExceptionCheck())
	{
		env->ExceptionClear();
	}

	env->DeleteLocalRef(cls);
	env->DeleteLocalRef(activity);
#endif
}

// Any thread. Takes its own copy of the text.
void UZDroid_QueueCommand (const char *command)
{
	if (command == nullptr || *command == 0 || CommandEventType == (Uint32)-1)
		return;

	SDL_Event sev;
	memset(&sev, 0, sizeof(sev));
	sev.type = CommandEventType;
	sev.user.code = 0;
	sev.user.data1 = new char[strlen(command) + 1];
	strcpy(static_cast<char *>(sev.user.data1), command);

	if (SDL_PushEvent(&sev) <= 0)
	{
		delete[] static_cast<char *>(sev.user.data1);
	}
}

// Game thread. Returns true when the event was ours.
bool UZDroid_HandleEvent (const SDL_Event &sev)
{
	if (CommandEventType == (Uint32)-1 || sev.type != CommandEventType)
		return false;

	char *command = static_cast<char *>(sev.user.data1);

	if (command != nullptr)
	{
		AddCommandString(command);
		delete[] command;
	}
	return true;
}

CCMD (uzdroid_touch_edit)
{
	TouchEditing = !TouchEditing;
	TouchEditIndex = -1;
	TouchStickRelease();

	TouchPushEditMode(TouchEditing);
	Printf("Touch layout editing %s: drag a button to move it.\n", TouchEditing ? "on" : "off");
}

CCMD (uzdroid_button_reset)
{
	TouchButtons.Clear();
}

// Sent by the Android overlay once per line of touch-buttons.txt:
//   uzdroid_button <x> <y> <w> <h> <input> <label>
// The overlay only draws them; the engine decides what a press means, which is
// also why a held button cannot swallow the other thumb's look gesture.
CCMD (uzdroid_button)
{
	if (argv.argc() < 7)
	{
		Printf("uzdroid_button <x> <y> <w> <h> <input> <label>\n");
		return;
	}

	FTouchButton button;

	button.x = (float)atof(argv[1]);
	button.y = (float)atof(argv[2]);
	button.w = (float)atof(argv[3]);
	button.h = (float)atof(argv[4]);

	FString input = argv[5];

	// '|' stands for ';' - a command chain cannot travel through the console
	// parser as one argument otherwise.
	input.Substitute("|", ";");

	if (!strnicmp(input, "mouse", 5))
	{
		button.mouse = atoi(input.GetChars() + 5);
		if (button.mouse < 1 || button.mouse > 3)
		{
			Printf("uzdroid_button: only mouse1..mouse3 are supported\n");
			return;
		}
	}
	else if (!strnicmp(input, "cmd:", 4))
	{
		button.command = input.GetChars() + 4;
	}
	else if (!strnicmp(input, "hold:", 5))
	{
		button.command = input.GetChars() + 5;
		button.hold = true;
	}
	else
	{
		button.key = TouchKeyFromName(input.GetChars());
		if (button.key < 0)
		{
			Printf("uzdroid_button: unknown input '%s'\n", input.GetChars());
			return;
		}
	}

	TouchButtons.Push(button);
}

CCMD (uzdroid_kbup)
{
	SDL_StartTextInput();
}

CCMD (uzdroid_kbdn)
{
	SDL_StopTextInput();
}

CCMD (uzdroid_kbtoggle)
{
	if (SDL_IsTextInputActive())
	{
		SDL_StopTextInput();
	}
	else
	{
		SDL_StartTextInput();
	}
}

#ifdef __ANDROID__
extern "C" JNIEXPORT void JNICALL Java_org_uzdoom_uzdroid_EngineBridge_exec
	(JNIEnv *env, jclass, jstring command)
{
	if (command == nullptr)
		return;

	const char *text = env->GetStringUTFChars(command, nullptr);

	if (text != nullptr)
	{
		UZDroid_QueueCommand(text);
		env->ReleaseStringUTFChars(command, text);
	}
}
#endif

void MessagePump (const SDL_Event &sev)
{
	static bool touchReady = false;

	EnsureCommandEvent();

	if (!touchReady)
	{
		touchReady = true;
		UZDroid_TouchReady();
	}

	if (UZDroid_HandleEvent(sev))
		return;

	TouchUpdateContext();

	static int lastx = 0, lasty = 0;
	int x, y;
	event_t event = { 0,0,0,0,0,0,0 };

	if (joykey_stop_conflict == -1 || joykey_stop_conflict == 1)
	{
		const int threshold = 1;

		static SDL_Window *window;
		static unsigned eventTimestamp;
		static bool seenKeyEvent, seenJoyEvent;
		static int duplicateEvents;

		if (eventTimestamp == 0)
		{
			eventTimestamp = duplicateEvents = 0;
			seenKeyEvent = seenJoyEvent = false;

			if (joykey_stop_conflict == 1)
				joykey_stop_conflict = -1;
		}

		if (joykey_stop_conflict == -1)
		{
			if (eventTimestamp != sev.common.timestamp)
			{
				if (seenKeyEvent != seenJoyEvent)
					duplicateEvents = 0;
				eventTimestamp = sev.common.timestamp;
				seenKeyEvent = seenJoyEvent = false;
			}

			switch (sev.type)
			{
				case SDL_KEYDOWN:
					seenKeyEvent = true;
					if (!window) window = SDL_GetWindowFromID(sev.key.windowID);
					break;
				case SDL_JOYBUTTONDOWN:
				case SDL_CONTROLLERBUTTONDOWN:
					seenJoyEvent = true;
					break;
			}

			if (seenJoyEvent && seenKeyEvent && ++duplicateEvents >= threshold)
			{
				joykey_stop_conflict = 1;

				// TODO: use CustomMessageBox https://github.com/ZDoom/gzdoom/pull/1821
				if (window) SDL_HideWindow(window);
				SDL_ShowSimpleMessageBox(
					SDL_MESSAGEBOX_INFORMATION,
					GAMENAME,
					"Simultaneous input from a gamepad and keyboard detected!\n"
					"All keyboard/mouse input has been temporarily disabled.\n"
					"\n"
					"If using a handheld PC, try switching from DESKTOP to GAMEPAD bindings.\n"
					"This can generally be done by holding down START.\n"
					"\n"
					"To disable this detection, set joykey_stop_conflict to 0.",
					window
				);
				if (window) SDL_ShowWindow(window);
			}
		}
	}

	switch (sev.type)
	{
	case SDL_QUIT:
		throw CExitEvent(0);

	case SDL_WINDOWEVENT:
		extern void ProcessSDLWindowEvent(const SDL_WindowEvent &);
		ProcessSDLWindowEvent(sev.window);
		break;

	case SDL_MOUSEBUTTONDOWN:
	case SDL_MOUSEBUTTONUP:
		if (joykey_stop_conflict > 0 && sev.type == SDL_MOUSEBUTTONDOWN) break;
		if (!GUICapture)
		{
			event.type = sev.type == SDL_MOUSEBUTTONDOWN ? EV_KeyDown : EV_KeyUp;

			switch (sev.button.button)
			{
			case SDL_BUTTON_LEFT:	event.data1 = KEY_MOUSE1;		break;
			case SDL_BUTTON_MIDDLE:	event.data1 = KEY_MOUSE3;		break;
			case SDL_BUTTON_RIGHT:	event.data1 = KEY_MOUSE2;		break;
			case SDL_BUTTON_X1:		event.data1 = KEY_MOUSE4;		break;
			case SDL_BUTTON_X2:		event.data1 = KEY_MOUSE5;		break;
			case 6:		event.data1 = KEY_MOUSE6;		break;
			case 7:		event.data1 = KEY_MOUSE7;		break;
			case 8:		event.data1 = KEY_MOUSE8;		break;
			default:	printf("SDL mouse button %s %d\n",
				sev.type == SDL_MOUSEBUTTONDOWN ? "down" : "up", sev.button.button);	break;
			}

			if (event.data1 != 0)
			{
				D_PostEvent(&event);
			}
		}
		else if ((sev.button.button >= SDL_BUTTON_LEFT && sev.button.button <= SDL_BUTTON_X2))
		{
			int x, y;
			SDL_GetMouseState(&x, &y);

			event.type = EV_GUI_Event;
			event.data1 = x;
			event.data2 = y;

			screen->ScaleCoordsFromWindow(event.data1, event.data2);

			if (sev.type == SDL_MOUSEBUTTONDOWN)
			{
				switch(sev.button.button)
				{
				case SDL_BUTTON_LEFT:   event.subtype = EV_GUI_LButtonDown;    break;
				case SDL_BUTTON_MIDDLE: event.subtype = EV_GUI_MButtonDown;    break;
				case SDL_BUTTON_RIGHT:  event.subtype = EV_GUI_RButtonDown;    break;
				case SDL_BUTTON_X1:     event.subtype = EV_GUI_BackButtonDown; break;
				case SDL_BUTTON_X2:     event.subtype = EV_GUI_FwdButtonDown;  break;
				default: assert(false); event.subtype = EV_GUI_None;           break;
				}
			}
			else
			{
				switch(sev.button.button)
				{
				case SDL_BUTTON_LEFT:   event.subtype = EV_GUI_LButtonUp;    break;
				case SDL_BUTTON_MIDDLE: event.subtype = EV_GUI_MButtonUp;    break;
				case SDL_BUTTON_RIGHT:  event.subtype = EV_GUI_RButtonUp;    break;
				case SDL_BUTTON_X1:     event.subtype = EV_GUI_BackButtonUp; break;
				case SDL_BUTTON_X2:     event.subtype = EV_GUI_FwdButtonUp;  break;
				default: assert(false); event.subtype = EV_GUI_None;         break;
				}
			}

			SDL_Keymod kmod = SDL_GetModState();
			event.data3 = ((kmod & KMOD_SHIFT) ? GKM_SHIFT : 0) |
				((kmod & KMOD_CTRL) ? GKM_CTRL : 0) |
				((kmod & KMOD_ALT) ? GKM_ALT : 0);

			D_PostEvent(&event);
		}
		break;

	case SDL_MOUSEMOTION:
		if (joykey_stop_conflict > 0) break;
		if (GUICapture)
		{
			event.data1 = sev.motion.x;
			event.data2 = sev.motion.y;

			screen->ScaleCoordsFromWindow(event.data1, event.data2);

			event.type = EV_GUI_Event;
			event.subtype = EV_GUI_MouseMove;

			SDL_Keymod kmod = SDL_GetModState();
			event.data3 = ((kmod & KMOD_SHIFT) ? GKM_SHIFT : 0) |
			              ((kmod & KMOD_CTRL) ? GKM_CTRL : 0) |
			              ((kmod & KMOD_ALT) ? GKM_ALT : 0);

			D_PostEvent(&event);
		}
		break;

	case SDL_MOUSEWHEEL:
		if (joykey_stop_conflict > 0) break;
		if (GUICapture)
		{
			event.type = EV_GUI_Event;

			if (sev.wheel.y == 0)
				event.subtype = sev.wheel.x > 0 ? EV_GUI_WheelRight : EV_GUI_WheelLeft;
			else
				event.subtype = sev.wheel.y > 0 ? EV_GUI_WheelUp : EV_GUI_WheelDown;

			SDL_Keymod kmod = SDL_GetModState();
			event.data3 = ((kmod & KMOD_SHIFT) ? GKM_SHIFT : 0) |
				((kmod & KMOD_CTRL) ? GKM_CTRL : 0) |
				((kmod & KMOD_ALT) ? GKM_ALT : 0);

			D_PostEvent (&event);
		}
		else
		{
			event.type = EV_KeyDown;

			if (sev.wheel.y != 0)
				event.data1 = sev.wheel.y > 0 ? KEY_MWHEELUP : KEY_MWHEELDOWN;
			else
				event.data1 = sev.wheel.x > 0 ? KEY_MWHEELRIGHT : KEY_MWHEELLEFT;

			D_PostEvent (&event);
			event.type = EV_KeyUp;
			D_PostEvent (&event);
		}
		break;

	case SDL_KEYDOWN:
	case SDL_KEYUP:
		if (joykey_stop_conflict > 0 && sev.type == SDL_KEYDOWN) break;
		if (!GUICapture)
		{
			if (sev.key.repeat)
			{
				break;
			}

			event.type = sev.type == SDL_KEYDOWN ? EV_KeyDown : EV_KeyUp;

			// Try to look up our key mapped key for conversion to DirectInput.
			// If that fails, then we'll do a lookup against the scan code,
			// which may not return the right key, but at least the key should
			// work in the game.
			if (const uint8_t *dik = KeySymToDIK.CheckKey (sev.key.keysym.sym))
				event.data1 = *dik;
			else if (const uint8_t *dik = KeyScanToDIK.CheckKey (sev.key.keysym.scancode))
				event.data1 = *dik;

			if (event.data1)
			{
				if (sev.key.keysym.sym < 256)
				{
					event.data2 = sev.key.keysym.sym;
				}
				SDL_Keymod kmod = SDL_GetModState();
				event.data3 = ((kmod & KMOD_SHIFT) ? GKM_SHIFT : 0) |
					((kmod & KMOD_CTRL) ? GKM_CTRL : 0) |
					((kmod & KMOD_ALT) ? GKM_ALT : 0);
				D_PostEvent (&event);
			}
		}
		else
		{
			event.type = EV_GUI_Event;
			event.subtype = sev.type == SDL_KEYDOWN ? EV_GUI_KeyDown : EV_GUI_KeyUp;
			SDL_Keymod kmod = SDL_GetModState();
			event.data3 = ((kmod & KMOD_SHIFT) ? GKM_SHIFT : 0) |
				((kmod & KMOD_CTRL) ? GKM_CTRL : 0) |
				((kmod & KMOD_ALT) ? GKM_ALT : 0);

			if (event.subtype == EV_GUI_KeyDown && sev.key.repeat)
			{
				event.subtype = EV_GUI_KeyRepeat;
			}

			switch (sev.key.keysym.sym)
			{
			case SDLK_KP_ENTER:	event.data1 = GK_RETURN;	break;
			case SDLK_PAGEUP:	event.data1 = GK_PGUP;		break;
			case SDLK_PAGEDOWN:	event.data1 = GK_PGDN;		break;
			case SDLK_END:		event.data1 = GK_END;		break;
			case SDLK_HOME:		event.data1 = GK_HOME;		break;
			case SDLK_LEFT:		event.data1 = GK_LEFT;		break;
			case SDLK_RIGHT:	event.data1 = GK_RIGHT;		break;
			case SDLK_UP:		event.data1 = GK_UP;		break;
			case SDLK_DOWN:		event.data1 = GK_DOWN;		break;
			case SDLK_DELETE:	event.data1 = GK_DEL;		break;
			case SDLK_ESCAPE:	event.data1 = GK_ESCAPE;	break;
			case SDLK_F1:		event.data1 = GK_F1;		break;
			case SDLK_F2:		event.data1 = GK_F2;		break;
			case SDLK_F3:		event.data1 = GK_F3;		break;
			case SDLK_F4:		event.data1 = GK_F4;		break;
			case SDLK_F5:		event.data1 = GK_F5;		break;
			case SDLK_F6:		event.data1 = GK_F6;		break;
			case SDLK_F7:		event.data1 = GK_F7;		break;
			case SDLK_F8:		event.data1 = GK_F8;		break;
			case SDLK_F9:		event.data1 = GK_F9;		break;
			case SDLK_F10:		event.data1 = GK_F10;		break;
			case SDLK_F11:		event.data1 = GK_F11;		break;
			case SDLK_F12:		event.data1 = GK_F12;		break;
			default:
				if (sev.key.keysym.sym < 256)
				{
					event.data1 = sev.key.keysym.sym;
				}
				break;
			}
			if (event.data1 < 128)
			{
				event.data1 = toupper(event.data1);
				D_PostEvent (&event);
			}
		}
		break;

	case SDL_TEXTINPUT:
		if (joykey_stop_conflict > 0 && sev.type == SDL_TEXTINPUT) break;
		if (GUICapture)
		{
			int size;

			int unichar = utf8_decode((const uint8_t*)sev.text.text, &size);
			if (size != 4)
			{
				event.type = EV_GUI_Event;
				event.subtype = EV_GUI_Char;
				event.data1 = (int16_t)unichar;
				event.data2 = !!(SDL_GetModState() & KMOD_ALT);
				D_PostEvent (&event);
			}
		}
		break;

	case SDL_JOYBUTTONDOWN:
	case SDL_JOYBUTTONUP:
		if (SDL_GameControllerFromInstanceID(sev.jdevice.which))
			break; // let SDL_CONTROLLERBUTTON* handle this
		event.type = sev.type == SDL_JOYBUTTONDOWN ? EV_KeyDown : EV_KeyUp;
		event.data1 = KEY_FIRSTJOYBUTTON + sev.jbutton.button;
		if(event.data1 != 0)
			I_JoyConsumeEvent(sev.jdevice.which, &event);
		break;

	case SDL_CONTROLLERBUTTONDOWN:
	case SDL_CONTROLLERBUTTONUP:
		event.type = sev.type == SDL_CONTROLLERBUTTONDOWN ? EV_KeyDown : EV_KeyUp;
		switch (sev.cbutton.button)
		{
			case SDL_CONTROLLER_BUTTON_A:             event.data1 = KEY_PAD_A;          break;
			case SDL_CONTROLLER_BUTTON_B:             event.data1 = KEY_PAD_B;          break;
			case SDL_CONTROLLER_BUTTON_X:             event.data1 = KEY_PAD_X;          break;
			case SDL_CONTROLLER_BUTTON_Y:             event.data1 = KEY_PAD_Y;          break;
			case SDL_CONTROLLER_BUTTON_BACK:          event.data1 = KEY_PAD_BACK;       break;
			case SDL_CONTROLLER_BUTTON_GUIDE:         event.data1 = KEY_PAD_GUIDE;      break;
			case SDL_CONTROLLER_BUTTON_START:         event.data1 = KEY_PAD_START;      break;
			case SDL_CONTROLLER_BUTTON_LEFTSTICK:     event.data1 = KEY_PAD_LTHUMB;     break;
			case SDL_CONTROLLER_BUTTON_RIGHTSTICK:    event.data1 = KEY_PAD_RTHUMB;     break;
			case SDL_CONTROLLER_BUTTON_LEFTSHOULDER:  event.data1 = KEY_PAD_LSHOULDER;  break;
			case SDL_CONTROLLER_BUTTON_RIGHTSHOULDER: event.data1 = KEY_PAD_RSHOULDER;  break;
			case SDL_CONTROLLER_BUTTON_DPAD_UP:       event.data1 = KEY_PAD_DPAD_UP;    break;
			case SDL_CONTROLLER_BUTTON_DPAD_DOWN:     event.data1 = KEY_PAD_DPAD_DOWN;  break;
			case SDL_CONTROLLER_BUTTON_DPAD_LEFT:     event.data1 = KEY_PAD_DPAD_LEFT;  break;
			case SDL_CONTROLLER_BUTTON_DPAD_RIGHT:    event.data1 = KEY_PAD_DPAD_RIGHT; break;
			case SDL_CONTROLLER_BUTTON_MISC1:         event.data1 = KEY_PAD_MISC1;      break;
			case SDL_CONTROLLER_BUTTON_PADDLE1:       event.data1 = KEY_PAD_PADDLE1;    break;
			case SDL_CONTROLLER_BUTTON_PADDLE2:       event.data1 = KEY_PAD_PADDLE2;    break;
			case SDL_CONTROLLER_BUTTON_PADDLE3:       event.data1 = KEY_PAD_PADDLE3;    break;
			case SDL_CONTROLLER_BUTTON_PADDLE4:       event.data1 = KEY_PAD_PADDLE4;    break;
			case SDL_CONTROLLER_BUTTON_TOUCHPAD:      event.data1 = KEY_PAD_TOUCHPAD;   break;
			default:                                  event.data1 = 0;
		}
		if(event.data1 != 0)
			I_JoyConsumeEvent(sev.cbutton.which, &event);
		break;

	case SDL_FINGERDOWN:
		TouchFingerDown(sev.tfinger);
		break;

	case SDL_FINGERMOTION:
		TouchFingerMotion(sev.tfinger);
		break;

	case SDL_FINGERUP:
		TouchFingerUp(sev.tfinger);
		break;

	case SDL_APP_WILLENTERBACKGROUND:
		// Losing the screen must not leave a movement key held down.
		TouchSuspend();
		break;

	case SDL_JOYDEVICEADDED:
	case SDL_CONTROLLERDEVICEADDED:
		if (sev.type == SDL_JOYDEVICEADDED && SDL_IsGameController(sev.jdevice.which)) // DeviceIndex Here
			break; // skip double event
		I_UpdateDeviceList();
		event.type = EV_DeviceChange;
		D_PostEvent (&event);
		break;

	case SDL_JOYDEVICEREMOVED:
	case SDL_CONTROLLERDEVICEREMOVED:
	case SDL_CONTROLLERDEVICEREMAPPED:
		if (sev.type == SDL_JOYDEVICEREMOVED && SDL_GameControllerFromInstanceID(sev.jdevice.which))
			break; // skip double event
		I_UpdateDeviceList();
		event.type = EV_DeviceChange;
		D_PostEvent (&event);
		break;
	}
}

#ifdef __ANDROID__
// Android port debug aid: the first few raw SDL events of each kind are written
// to the engine log, which is what makes a phantom input device identifiable
// (a stuck axis, a key that never repeats an up event, touch-as-mouse motion).
// Capped per kind so a finger held on the screen cannot flood the log.
static void TraceInputEvent(const SDL_Event &sev)
{
	static int budget[8] = { 25, 25, 25, 25, 25, 25, 25, 25 };
	int slot = -1;

	switch (sev.type)
	{
	case SDL_KEYDOWN:
	case SDL_KEYUP:
		slot = 0;
		if (budget[slot]-- <= 0) return;
		Printf("IN key %s scancode=%d sym=%d repeat=%d\n",
			sev.type == SDL_KEYDOWN ? "down" : "up",
			sev.key.keysym.scancode, sev.key.keysym.sym, sev.key.repeat);
		return;
	case SDL_MOUSEMOTION:
		slot = 1;
		if (budget[slot]-- <= 0) return;
		Printf("IN mouse motion x=%d y=%d xrel=%d yrel=%d\n",
			sev.motion.x, sev.motion.y, sev.motion.xrel, sev.motion.yrel);
		return;
	case SDL_MOUSEBUTTONDOWN:
	case SDL_MOUSEBUTTONUP:
		slot = 2;
		if (budget[slot]-- <= 0) return;
		Printf("IN mouse %s button=%d x=%d y=%d\n",
			sev.type == SDL_MOUSEBUTTONDOWN ? "down" : "up",
			sev.button.button, sev.button.x, sev.button.y);
		return;
	case SDL_JOYAXISMOTION:
	case SDL_CONTROLLERAXISMOTION:
		slot = 3;
		if (budget[slot]-- <= 0) return;
		Printf("IN joy axis=%d value=%d\n", sev.jaxis.axis, sev.jaxis.value);
		return;
	case SDL_JOYHATMOTION:
		slot = 4;
		if (budget[slot]-- <= 0) return;
		Printf("IN joy hat=%d value=%d\n", sev.jhat.hat, sev.jhat.value);
		return;
	case SDL_JOYBUTTONDOWN:
	case SDL_JOYBUTTONUP:
	case SDL_CONTROLLERBUTTONDOWN:
	case SDL_CONTROLLERBUTTONUP:
		slot = 5;
		if (budget[slot]-- <= 0) return;
		Printf("IN joy button=%d value=%d\n", sev.jbutton.button, sev.jbutton.state);
		return;
	case SDL_FINGERDOWN:
	case SDL_FINGERUP:
	case SDL_FINGERMOTION:
		slot = 6;
		if (budget[slot]-- <= 0) return;
		Printf("IN finger type=%d id=%d x=%.3f y=%.3f\n",
			sev.type, int(sev.tfinger.fingerId), sev.tfinger.x, sev.tfinger.y);
		return;
	default:
		return;
	}
}
#endif

void I_GetEvent ()
{
	SDL_Event sev;

	while (SDL_PollEvent (&sev))
	{
#ifdef __ANDROID__
		TraceInputEvent(sev);
#endif
		MessagePump (sev);
	}
	if (use_mouse)
	{
		MouseRead ();
	}
}

void I_StartTic ()
{
	I_CheckGUICapture ();
	I_CheckNativeMouse ();
	I_GetEvent ();
	Joy_RumbleTick();
}

void I_ProcessJoysticks ();
void I_StartFrame ()
{
	I_ProcessJoysticks();
}
