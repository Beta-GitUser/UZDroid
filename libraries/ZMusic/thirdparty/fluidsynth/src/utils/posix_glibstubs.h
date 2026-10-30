/*
** posix_glibstubs.h
**
** FluidSynth only needs a handful of glib primitives, and its Windows build
** already ships stubs for them (win32_glibstubs.h). Android's NDK has no glib
** at all and no way to package one, so this is the same set of primitives
** implemented on top of pthreads, POSIX threads' atomics and stat().
**
** It is used when WITH_GLIB_STUBS is defined for a non-Windows target, which
** makes FluidSynth take its "glib older than 2.32" threading code path - the
** same one the Windows stubs select.
**
** SPDX-License-Identifier: LGPL-2.1-or-later
*/

#ifndef _POSIXGLIBSTUBS_H
#define _POSIXGLIBSTUBS_H

#include <assert.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

/* Evaluate to 0 so that FluidSynth uses the "old" thread API, exactly like the
 * Windows stubs do. The reported version must stay below 2.30, because the
 * atomic exchange-and-add macros are selected by it. */
#define GLIB_CHECK_VERSION(x, y, z) 0
#define GLIB_MAJOR_VERSION 2
#define GLIB_MINOR_VERSION 29

typedef struct
{
    int code;
    const char *message;
} GError;
typedef void *gpointer;

#define g_new(s, c) FLUID_ARRAY(s, c)
#define g_free(p) FLUID_FREE(p)
#define g_strfreev FLUID_FREE
#define g_newa(_type, _len) (_type *) __builtin_alloca(sizeof(_type) * (_len))
#define g_assert(a) assert(a)

#ifndef TRUE
#define TRUE 1
#endif
#ifndef FALSE
#define FALSE 0
#endif
#define G_LIKELY(expr) (expr)
#define G_UNLIKELY(expr) (expr)

#define g_vsnprintf(b, c, f, a) vsnprintf(b, c, f, a)
#define g_snprintf(b, c, f, ...) snprintf(b, c, f, __VA_ARGS__)

#define g_return_val_if_fail(expr, val) if (expr) {} else { return val; }
#define g_clear_error(err) do {} while (0)

#define G_FILE_TEST_EXISTS 1
#define G_FILE_TEST_IS_REGULAR 2

#define g_file_test fluid_g_file_test
int fluid_g_file_test(const char *path, int flags);

/* glib's gstdio.h spells stat() this way. */
#define g_stat(path, buf) stat((path), (buf))
#define g_fopen(path, mode) fopen((path), (mode))
#define g_remove(path) remove(path)

#define g_shell_parse_argv fluid_g_shell_parse_argv
int fluid_g_shell_parse_argv(const char *command_line, int *argcp, char ***argvp, void *dummy);

#define g_get_monotonic_time fluid_g_get_monotonic_time
double fluid_g_get_monotonic_time(void);

/* Byte ordering */
#ifdef __BYTE_ORDER__
#define G_BYTE_ORDER __BYTE_ORDER__
#define G_BIG_ENDIAN __ORDER_BIG_ENDIAN__
#else
/* If __BYTE_ORDER__ isn't defined, assume little endian */
#define G_BYTE_ORDER 1234
#define G_BIG_ENDIAN 4321
#endif

#if G_BYTE_ORDER == G_BIG_ENDIAN
#define GINT16_FROM_LE(x) (int16_t)(((uint16_t)(x) >> 8) | ((uint16_t)(x) << 8))
#define GINT32_FROM_LE(x) (int32_t)((FLUID_LE16TOH(x) << 16) | (FLUID16_LE16TOH(x >> 16)))
#else
#define GINT32_FROM_LE(x) (x)
#define GINT16_FROM_LE(x) (x)

/* Thread support */

#define g_thread_supported() 1
#define g_thread_init(_) do {} while (0)
#define g_usleep(usecs) usleep((usecs))

typedef gpointer (*GThreadFunc)(void *data);
typedef struct
{
    GThreadFunc func;
    void *data;
    pthread_t handle;
    int joinable;
} GThread;

#define g_thread_create fluid_g_thread_create
#define g_thread_join fluid_g_thread_join
GThread *fluid_g_thread_create(GThreadFunc func, void *data, int joinable, GError **error);
void fluid_g_thread_join(GThread *thread);

/* Regular mutex */
typedef pthread_mutex_t GStaticMutex;
#define G_STATIC_MUTEX_INIT PTHREAD_MUTEX_INITIALIZER
#define g_static_mutex_init(_m) pthread_mutex_init((_m), NULL)
#define g_static_mutex_free(_m) pthread_mutex_destroy(_m)
#define g_static_mutex_lock(_m) pthread_mutex_lock(_m)
#define g_static_mutex_unlock(_m) pthread_mutex_unlock(_m)

/* Recursive lock capable mutex */
typedef pthread_mutex_t GStaticRecMutex;

static inline void
fluid_g_static_rec_mutex_init(GStaticRecMutex *mutex)
{
    pthread_mutexattr_t attributes;
    pthread_mutexattr_init(&attributes);
    pthread_mutexattr_settype(&attributes, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(mutex, &attributes);
    pthread_mutexattr_destroy(&attributes);
}

#define g_static_rec_mutex_init(_m) fluid_g_static_rec_mutex_init(_m)
#define g_static_rec_mutex_free(_m) pthread_mutex_destroy(_m)
#define g_static_rec_mutex_lock(_m) pthread_mutex_lock(_m)
#define g_static_rec_mutex_unlock(_m) pthread_mutex_unlock(_m)

/* Dynamically allocated mutex suitable for fluid_cond_t use */
typedef pthread_mutex_t GMutex;
#define g_mutex_lock(m) pthread_mutex_lock(m)
#define g_mutex_unlock(m) pthread_mutex_unlock(m)

static inline GMutex *
g_mutex_new(void)
{
    GMutex *mutex = g_new(GMutex, 1);

    if(mutex != NULL)
    {
        pthread_mutex_init(mutex, NULL);
    }

    return mutex;
}

static inline void
fluid_g_mutex_free(GMutex *mutex)
{
    if(mutex != NULL)
    {
        pthread_mutex_destroy(mutex);
        g_free(mutex);
    }
}

#define g_mutex_free(m) fluid_g_mutex_free(m)

/* Thread condition signaling */
typedef pthread_cond_t GCond;
#define g_cond_signal(cond) pthread_cond_signal(cond)
#define g_cond_broadcast(cond) pthread_cond_broadcast(cond)
#define g_cond_wait(cond, mutex) pthread_cond_wait(cond, mutex)

static inline GCond *
g_cond_new(void)
{
    GCond *cond = g_new(GCond, 1);

    if(cond != NULL)
    {
        pthread_cond_init(cond, NULL);
    }

    return cond;
}

static inline void
fluid_g_cond_free(GCond *cond)
{
    if(cond != NULL)
    {
        pthread_cond_destroy(cond);
        g_free(cond);
    }
}

#define g_cond_free(cond) fluid_g_cond_free(cond)

/* Thread private data */
typedef pthread_key_t GStaticPrivate;
#define g_static_private_init(_priv) pthread_key_create((_priv), NULL)
#define g_static_private_get(_priv) pthread_getspecific(*(_priv))
#define g_static_private_set(_priv, _data, _) pthread_setspecific(*(_priv), _data)
#define g_static_private_free(_priv) pthread_key_delete(*(_priv))

/* Atomic operations. The compare-and-exchange forms take their "expected" value
 * by value: taking its address, as glib does, would require an lvalue at every
 * call site. */
static inline int
fluid_g_atomic_int_compare_and_exchange(int *atomic, int old_value, int new_value)
{
    return __atomic_compare_exchange_n(atomic, &old_value, new_value, 0,
                                       __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
}

static inline int
fluid_g_atomic_pointer_compare_and_exchange(void **atomic, void *old_value, void *new_value)
{
    return __atomic_compare_exchange_n(atomic, &old_value, new_value, 0,
                                       __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
}

#define g_atomic_int_inc(_pi) __atomic_add_fetch((_pi), 1, __ATOMIC_SEQ_CST)
#define g_atomic_int_get(_pi) __atomic_load_n((_pi), __ATOMIC_SEQ_CST)
#define g_atomic_int_set(_pi, _val) __atomic_store_n((_pi), (_val), __ATOMIC_SEQ_CST)
#define g_atomic_int_dec_and_test(_pi) (__atomic_sub_fetch((_pi), 1, __ATOMIC_SEQ_CST) == 0)
#define g_atomic_int_compare_and_exchange(_pi, _old, _new) \
    fluid_g_atomic_int_compare_and_exchange((_pi), (_old), (_new))
#define g_atomic_int_add(_pi, _add) __atomic_fetch_add((_pi), (_add), __ATOMIC_SEQ_CST)
#define g_atomic_int_exchange_and_add(_pi, _add) __atomic_fetch_add((_pi), (_add), __ATOMIC_SEQ_CST)

#define g_atomic_pointer_get(_pp) __atomic_load_n((_pp), __ATOMIC_SEQ_CST)
#define g_atomic_pointer_set(_pp, _val) __atomic_store_n((_pp), (_val), __ATOMIC_SEQ_CST)
#define g_atomic_pointer_compare_and_exchange(_pp, _old, _new) \
    fluid_g_atomic_pointer_compare_and_exchange((_pp), (void *)(_old), (void *)(_new))

#endif /* little endian */

#endif /* _POSIXGLIBSTUBS_H */
