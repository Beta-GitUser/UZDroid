/*
** i_android_log.cpp
**
** Android specific start-up helpers.
**
** On Android there is no console to look at, so everything the engine prints
** would be lost. We therefore redirect stdout/stderr into a pipe and drain it
** from a helper thread into logcat and (optionally) into a log file whose path
** the Java launcher passes in UZDOOM_LOG.
**
**---------------------------------------------------------------------------
** SPDX-License-Identifier: GPL-3.0-or-later
**---------------------------------------------------------------------------
**
*/

#ifdef __ANDROID__

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <android/log.h>

#include "i_android_log.h"

namespace
{
	const char* const LOG_TAG = "UZDoom";

	int    ReadPipe = -1;
	FILE*  LogFile = nullptr;

	void WriteLine(const char* line, size_t length)
	{
		if (length == 0)
			return;

		__android_log_write(ANDROID_LOG_INFO, LOG_TAG, line);

		if (LogFile != nullptr)
		{
			fwrite(line, 1, length, LogFile);
			fputc('\n', LogFile);
			fflush(LogFile);
		}
	}

	void* LogWriterThread(void*)
	{
		static const size_t MaxLine = 8192;
		char* line = (char*)malloc(MaxLine);
		char chunk[4096];

		if (line == nullptr)
			return nullptr;

		size_t lineLength = 0;
		ssize_t count;

		while ((count = read(ReadPipe, chunk, sizeof chunk)) > 0)
		{
			for (ssize_t i = 0; i < count; ++i)
			{
				const char c = chunk[i];

				if (c == '\r')
					continue;

				if (c == '\n')
				{
					line[lineLength] = '\0';
					WriteLine(line, lineLength);
					lineLength = 0;
					continue;
				}

				// Long lines are flushed in pieces rather than truncated.
				if (lineLength + 1 >= MaxLine)
				{
					line[lineLength] = '\0';
					WriteLine(line, lineLength);
					lineLength = 0;
				}

				line[lineLength++] = c;
			}
		}

		if (lineLength > 0)
		{
			line[lineLength] = '\0';
			WriteLine(line, lineLength);
		}

		free(line);
		return nullptr;
	}
}

void I_Android_InitLogging()
{
	const char* logPath = getenv("UZDOOM_LOG");

	if (logPath != nullptr && *logPath != '\0')
	{
		LogFile = fopen(logPath, "a");
		if (LogFile == nullptr)
		{
			__android_log_print(ANDROID_LOG_WARN, LOG_TAG, "Unable to open log file '%s': %s", logPath, strerror(errno));
		}
	}

	int fds[2];

	if (pipe(fds) != 0)
	{
		__android_log_print(ANDROID_LOG_WARN, LOG_TAG, "Unable to create log pipe: %s", strerror(errno));
		return;
	}

	if (dup2(fds[1], STDOUT_FILENO) < 0 || dup2(fds[1], STDERR_FILENO) < 0)
	{
		__android_log_print(ANDROID_LOG_WARN, LOG_TAG, "Unable to redirect stdout/stderr: %s", strerror(errno));
		close(fds[0]);
		close(fds[1]);
		return;
	}

	close(fds[1]);
	ReadPipe = fds[0];

	// Make sure the engine's console output is flushed as it happens instead of
	// sitting in a 4k pipe buffer.
	setvbuf(stdout, nullptr, _IOLBF, 0);
	setvbuf(stderr, nullptr, _IONBF, 0);

	pthread_t thread;

	if (pthread_create(&thread, nullptr, LogWriterThread, nullptr) == 0)
	{
		pthread_detach(thread);
	}
	else
	{
		__android_log_print(ANDROID_LOG_WARN, LOG_TAG, "Unable to start the log thread");
	}
}

#endif // __ANDROID__
