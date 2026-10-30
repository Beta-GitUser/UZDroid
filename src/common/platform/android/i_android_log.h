/*
** i_android_log.h
**
** Android specific start-up helpers.
**
**---------------------------------------------------------------------------
** SPDX-License-Identifier: GPL-3.0-or-later
**---------------------------------------------------------------------------
**
*/

#ifndef I_ANDROID_LOG_H
#define I_ANDROID_LOG_H

#ifdef __ANDROID__

//
// Redirects stdout/stderr into a pipe that a helper thread drains into both
// logcat and, if the UZDOOM_LOG environment variable points somewhere
// writable, into that file. This has to be called before anything in the
// engine writes to the console.
//
void I_Android_InitLogging();

#endif // __ANDROID__

#endif // I_ANDROID_LOG_H
