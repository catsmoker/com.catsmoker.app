package com.catsmoker.app;

import com.catsmoker.app.shizuku.CommandResult;

interface IFileService {
    void destroy();
    int executeCommand(in String[] command);
    List<String> executeAndGetOutput(in String[] command);

    /** Like executeAndGetOutput but also reports the exit code and stderr separately. */
    CommandResult executeForResult(in String[] command);

    /**
     * Sweeps /sys/class/thermal/thermal_zone* inside the privileged process and returns
     * "type:millidegrees" lines. Avoids forking a shell on every metrics poll.
     */
    String readSysfsThermal();

    /**
     * Returns the contents of /proc/stat, which SELinux hides from untrusted_app on modern
     * Android. Reading it here costs one binder call instead of forking `cat` every poll.
     */
    String readProcStat();
}
