/*
** posix_glibstubs.c
**
** The parts of the glib stub set that cannot be macros: file tests, the
** monotonic clock, threads and a small command line splitter. See
** posix_glibstubs.h for why this exists.
**
** SPDX-License-Identifier: LGPL-2.1-or-later
*/

#include "fluid_sys.h"
#include "posix_glibstubs.h"

#include <errno.h>

int fluid_g_file_test(const char *path, int flags)
{
    struct stat info;

    if(path == NULL || stat(path, &info) != 0)
    {
        return 0;
    }

    if(flags & G_FILE_TEST_EXISTS)
    {
        return 1;
    }

    if(flags & G_FILE_TEST_IS_REGULAR)
    {
        return S_ISREG(info.st_mode) ? 1 : 0;
    }

    return 0;
}

/* Microseconds since an arbitrary point, matching what glib returns. */
double fluid_g_get_monotonic_time(void)
{
    struct timespec now;

    if(clock_gettime(CLOCK_MONOTONIC, &now) != 0)
    {
        return 0.0;
    }

    return (double)now.tv_sec * 1000000.0 + (double)now.tv_nsec / 1000.0;
}

static void *fluid_g_thread_trampoline(void *info_)
{
    GThread *info = (GThread *)info_;

    info->func(info->data);

    /* A detached thread owns its own bookkeeping; see fluid_g_thread_create. */
    if(!info->joinable)
    {
        FLUID_FREE(info);
    }

    return NULL;
}

GThread *fluid_g_thread_create(GThreadFunc func, void *data, int joinable, GError **error)
{
    GThread *info;

    if(error != NULL)
    {
        *error = NULL;
    }

    info = FLUID_ARRAY(GThread, 1);

    if(info == NULL)
    {
        return NULL;
    }

    info->func = func;
    info->data = data;
    info->joinable = joinable;

    if(pthread_create(&info->handle, NULL, fluid_g_thread_trampoline, info) != 0)
    {
        FLUID_FREE(info);
        return NULL;
    }

    if(!joinable)
    {
        pthread_detach(info->handle);
    }

    return info;
}

void fluid_g_thread_join(GThread *thread)
{
    if(thread == NULL)
    {
        return;
    }

    pthread_join(thread->handle, NULL);
    FLUID_FREE(thread);
}

/*
 * Splits a command line the way g_shell_parse_argv() does for the simple case
 * FluidSynth cares about: whitespace separated, double quotes group. The
 * strings and the pointer table share one allocation so that a single
 * g_strfreev() (which is FLUID_FREE here, as on Windows) releases everything.
 */
int fluid_g_shell_parse_argv(const char *command_line, int *argcp, char ***argvp, void *dummy)
{
    const char *scan;
    char *block;
    char **argv;
    char *out;
    int count = 1;
    int quoted = 0;
    size_t length;

    (void) dummy;

    if(command_line == NULL || argcp == NULL || argvp == NULL)
    {
        return 0;
    }

    for(scan = command_line; *scan != '\0'; scan++)
    {
        if(*scan == '"')
        {
            quoted = !quoted;
        }
        else if(!quoted && (*scan == ' ' || *scan == '\t'))
        {
            /* Only count a run of separators once. */
            if(scan[1] != '\0' && scan[1] != ' ' && scan[1] != '\t')
            {
                count++;
            }
        }
    }

    length = strlen(command_line) + 1;
    block = FLUID_ARRAY(char, length + (size_t)(count + 1) * sizeof(char *));

    if(block == NULL)
    {
        return 0;
    }

    argv = (char **)(block + length);
    scan = command_line;
    out = block;
    count = 0;
    quoted = 0;

    while(*scan != '\0')
    {
        while(*scan == ' ' || *scan == '\t')
        {
            scan++;
        }

        if(*scan == '\0')
        {
            break;
        }

        argv[count++] = out;

        while(*scan != '\0' && (quoted || (*scan != ' ' && *scan != '\t')))
        {
            if(*scan == '"')
            {
                quoted = !quoted;
                scan++;
                continue;
            }

            *out++ = *scan++;
        }

        *out++ = '\0';
    }

    argv[count] = NULL;
    *argcp = count;
    *argvp = argv;

    return 1;
}
