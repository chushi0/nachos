package nachos.network;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;

/**
 * A <tt>VMProcess</tt> that supports networking syscalls.
 */
public class NetProcess extends VMProcess {
    /**
     * Allocate a new process.
     */
    public NetProcess() {
        super();
    }

    private static final int
            syscallConnect = 11,
            syscallAccept = 12;

    /**
     * Handle a syscall exception. Called by <tt>handleException()</tt>. The
     * <i>syscall</i> argument identifies which syscall the user executed:
     *
     * <table>
     * <tr><td>syscall#</td><td>syscall prototype</td></tr>
     * <tr><td>11</td><td><tt>int  connect(int host, int port);</tt></td></tr>
     * <tr><td>12</td><td><tt>int  accept(int port);</tt></td></tr>
     * </table>
     *
     * @param syscall the syscall number.
     * @param a0      the first syscall argument.
     * @param a1      the second syscall argument.
     * @param a2      the third syscall argument.
     * @param a3      the fourth syscall argument.
     * @return the value to be returned to the user.
     */
    public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
        switch (syscall) {
            case syscallConnect:
                return handleConnect(a0, a1);
            case syscallAccept:
                return handleAccept(a0);
            default:
                return super.handleSyscall(syscall, a0, a1, a2, a3);
        }
    }

    private int handleConnect(int ipAddress, int port) {
        int index = nextOpenFileIndex();
        if (index == -1) return -1;
        if (port < 0 || port > NetworkService.maxPort) return -1;
        OpenFile file = NetKernel.networkService.connect(ipAddress, port);
        if (file == null) return -1;
        openFiles[index] = file;
        return index;
    }

    private int handleAccept(int port) {
        int index = nextOpenFileIndex();
        if (index == -1) return -1;
        if (port < 0 || port > NetworkService.maxPort) return -1;
        OpenFile file = NetKernel.networkService.accept(port);
        if (file == null) return -1;
        openFiles[index] = file;
        return index;
    }
}
