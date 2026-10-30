/*
** sdl_include.h
**
** SDL is spelled <SDL2/SDL.h> when it comes from a system package, and plain
** <SDL.h> when it is built from source as part of this build - which is what
** the Android port does, because the SDL2 source tree keeps its headers flat
** in include/. Including whichever of the two the compiler can actually see
** keeps both ways of building working.
**
** SPDX-License-Identifier: GPL-3.0-or-later
**
**---------------------------------------------------------------------------
**
*/

#ifndef UZDOOM_SDL_INCLUDE_H
#define UZDOOM_SDL_INCLUDE_H

#if defined(__has_include)
#	if __has_include(<SDL2/SDL.h>)
#		include <SDL2/SDL.h>
#	elif __has_include(<SDL.h>)
#		include <SDL.h>
#	else
#		error "SDL headers were not found, see src/common/platform/posix/sdl/sdl_include.h"
#	endif
#else
#	include <SDL2/SDL.h>
#endif

#endif
