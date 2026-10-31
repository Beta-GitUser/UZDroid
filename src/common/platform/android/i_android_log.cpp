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

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <unwind.h>

#include <android/log.h>

#include "i_android_log.h"

namespace
{
	const char* const LOG_TAG = "UZDoom";

	int    ReadPipe = -1;
	FILE*  LogFile = nullptr;
	int    CrashFd = -1;

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

	//
	// Crash reporting. A native crash takes the whole process with it, so the
	// only thing that can explain it afterwards is what was written before the
	// process died. Everything here uses write() on a raw descriptor, because
	// stdio is not safe to touch in a signal handler.
	//
	void CrashWrite(const char* text)
	{
		if (CrashFd >= 0)
		{
			const size_t length = strlen(text);
			const ssize_t written = write(CrashFd, text, length);
			(void)written;
		}
		__android_log_write(ANDROID_LOG_FATAL, LOG_TAG, text);
	}

	void CrashWriteHex(const char* label, uintptr_t value)
	{
		char buffer[64];
		snprintf(buffer, sizeof buffer, "%s0x%lx\n", label, (unsigned long)value);
		CrashWrite(buffer);
	}

	struct BacktraceState
	{
		int Depth;
	};

	_Unwind_Reason_Code CrashBacktraceFrame(_Unwind_Context* context, void* arg)
	{
		BacktraceState* state = static_cast<BacktraceState*>(arg);
		if (state->Depth >= 24)
			return _URC_END_OF_STACK;

		uintptr_t ip = static_cast<uintptr_t>(_Unwind_GetIP(context));
		if (ip == 0)
			return _URC_END_OF_STACK;

		char buffer[512];
		const int depth = state->Depth++;

		Dl_info info;
		if (dladdr(reinterpret_cast<void*>(ip), &info) != 0 && info.dli_fname != nullptr)
		{
			const char* name = info.dli_sname != nullptr ? info.dli_sname : "?";
			snprintf(buffer, sizeof buffer, "  #%d %s + 0x%lx (%s)\n", depth, info.dli_fname,
				(unsigned long)(ip - reinterpret_cast<uintptr_t>(info.dli_fbase)), name);
		}
		else
		{
			snprintf(buffer, sizeof buffer, "  #%d 0x%lx\n", depth, (unsigned long)ip);
		}

		// snprintf truncates instead of overflowing, so make sure the line ends.
		const size_t length = strlen(buffer);
		if (length > 0 && buffer[length - 1] != '\n')
		{
			buffer[length > sizeof buffer - 2 ? sizeof buffer - 2 : length] = '\n';
			buffer[length > sizeof buffer - 2 ? sizeof buffer - 1 : length + 1] = '\0';
		}

		CrashWrite(buffer);
		return _URC_NO_REASON;
	}

	void CrashHandler(int sig, siginfo_t* info, void* /*context*/)
	{
		// The header goes out first: if anything below misbehaves, the signal,
		// the address and the fact that we got here are already on disk.
		CrashWrite("\n*** native crash: ");
		switch (sig)
		{
		case SIGSEGV: CrashWrite("SIGSEGV"); break;
		case SIGBUS:  CrashWrite("SIGBUS"); break;
		case SIGILL:  CrashWrite("SIGILL"); break;
		case SIGFPE:  CrashWrite("SIGFPE"); break;
		case SIGABRT: CrashWrite("SIGABRT"); break;
		default:      CrashWrite("signal"); break;
		}
		CrashWrite(" ***\n");

		if (info != nullptr)
			CrashWriteHex("fault address ", reinterpret_cast<uintptr_t>(info->si_addr));

		CrashWrite("backtrace:\n");
		BacktraceState state;
		state.Depth = 0;
		_Unwind_Backtrace(CrashBacktraceFrame, &state);
		CrashWrite("*** end of crash report ***\n");

		if (CrashFd >= 0)
		{
			fsync(CrashFd);
		}

		// Let the system produce its own tombstone as well.
		signal(sig, SIG_DFL);
		raise(sig);
	}

	void InstallCrashHandlers()
	{
		if (LogFile != nullptr)
		{
			CrashFd = dup(fileno(LogFile));
		}

		static char altstack[64 * 1024];
		stack_t stack;
		memset(&stack, 0, sizeof stack);
		stack.ss_sp = altstack;
		stack.ss_size = sizeof altstack;
		sigaltstack(&stack, nullptr);

		struct sigaction action;
		memset(&action, 0, sizeof action);
		action.sa_sigaction = CrashHandler;
		action.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_RESETHAND;
		sigemptyset(&action.sa_mask);

		const int signals[] = { SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT };
		for (int sig : signals)
		{
			sigaction(sig, &action, nullptr);
		}
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

	InstallCrashHandlers();
}

#endif // __ANDROID__
