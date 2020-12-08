package nachos.userprog;

import nachos.machine.*;
import nachos.threads.KThread;
import nachos.threads.ThreadedKernel;

import java.io.EOFException;
import java.util.HashMap;

/**
 * Encapsulates the state of a user process that is not contained in its
 * user thread (or threads). This includes its address translation state, a
 * file table, and information about the program being executed.
 *
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 *
 * @see nachos.vm.VMProcess
 * @see nachos.network.NetProcess
 */
public class UserProcess {

    /**
     * Allocate a new process.
     */
    public UserProcess() {
        userProcessHashMap.put(id, this);
    }

    /**
     * Allocate and return a new process of the correct class. The class name
     * is specified by the <tt>nachos.conf</tt> key
     * <tt>Kernel.processClassName</tt>.
     *
     * @return a new process of the correct class.
     */
    public static UserProcess newUserProcess() {
        return (UserProcess) Lib.constructObject(Machine.getProcessClassName());
    }

    /**
     * Execute the specified program with the specified arguments. Attempts to
     * load the program, and then forks a thread to run it.
     *
     * @param name the name of the file containing the executable.
     * @param args the arguments to pass to the executable.
     * @return <tt>true</tt> if the program was successfully executed.
     */
    public boolean execute(String name, String[] args) {
        if (!load(name, args))
            return false;

        // 加载标准输入和标准输出
        SynchConsole synchConsole = UserKernel.console;
        openFiles[0] = synchConsole.openForReading();
        openFiles[1] = synchConsole.openForWriting();

        uThread.setName(name).fork();

        return true;
    }

    /**
     * Save the state of this process in preparation for a context switch.
     * Called by <tt>UThread.saveState()</tt>.
     */
    public void saveState() {
    }

    /**
     * Restore the state of this process after a context switch. Called by
     * <tt>UThread.restoreState()</tt>.
     */
    public void restoreState() {
        Machine.processor().setPageTable(pageTable);
    }

    /**
     * Read a null-terminated string from this process's virtual memory. Read
     * at most <tt>maxLength + 1</tt> bytes from the specified address, search
     * for the null terminator, and convert it to a <tt>java.lang.String</tt>,
     * without including the null terminator. If no null terminator is found,
     * returns <tt>null</tt>.
     *
     * @param vaddr     the starting virtual address of the null-terminated
     *                  string.
     * @param maxLength the maximum number of characters in the string,
     *                  not including the null terminator.
     * @return the string read, or <tt>null</tt> if no null terminator was
     * found.
     */
    public String readVirtualMemoryString(int vaddr, int maxLength) {
        Lib.assertTrue(maxLength >= 0);

        byte[] bytes = new byte[maxLength + 1];

        int bytesRead = readVirtualMemory(vaddr, bytes);

        for (int length = 0; length < bytesRead; length++) {
            if (bytes[length] == 0)
                return new String(bytes, 0, length);
        }

        return null;
    }

    /**
     * Transfer data from this process's virtual memory to all of the specified
     * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
     *
     * @param vaddr the first byte of virtual memory to read.
     * @param data  the array where the data will be stored.
     * @return the number of bytes successfully transferred.
     */
    public int readVirtualMemory(int vaddr, byte[] data) {
        return readVirtualMemory(vaddr, data, 0, data.length);
    }

    /**
     * Transfer data from this process's virtual memory to the specified array.
     * This method handles address translation details. This method must
     * <i>not</i> destroy the current process if an error occurs, but instead
     * should return the number of bytes successfully copied (or zero if no
     * data could be copied).
     *
     * @param vaddr  the first byte of virtual memory to read.
     * @param data   the array where the data will be stored.
     * @param offset the first byte to write in the array.
     * @param length the number of bytes to transfer from virtual memory to
     *               the array.
     * @return the number of bytes successfully transferred.
     */
    public int readVirtualMemory(int vaddr, byte[] data, int offset,
                                 int length) {
        Lib.assertTrue(offset >= 0 && length >= 0 && offset + length <= data.length);

        byte[] memory = Machine.processor().getMemory();

        int page = vaddr / Processor.pageSize;
        int start = vaddr % Processor.pageSize;

        int amount = 0;
        int remain = length;

        while (remain > 0) {

            if (page >= pageTable.length) break;

            int count = Math.min(Processor.pageSize - start, remain);
            int physicalStart = pageTable[page].ppn * Processor.pageSize + start;
            System.arraycopy(memory, physicalStart, data, offset, count);
            amount += count;
            remain -= count;

            page++;
            start = 0;
        }

        return amount;
    }

    /**
     * Transfer all data from the specified array to this process's virtual
     * memory.
     * Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
     *
     * @param vaddr the first byte of virtual memory to write.
     * @param data  the array containing the data to transfer.
     * @return the number of bytes successfully transferred.
     */
    public int writeVirtualMemory(int vaddr, byte[] data) {
        return writeVirtualMemory(vaddr, data, 0, data.length);
    }

    /**
     * Transfer data from the specified array to this process's virtual memory.
     * This method handles address translation details. This method must
     * <i>not</i> destroy the current process if an error occurs, but instead
     * should return the number of bytes successfully copied (or zero if no
     * data could be copied).
     *
     * @param vaddr  the first byte of virtual memory to write.
     * @param data   the array containing the data to transfer.
     * @param offset the first byte to transfer from the array.
     * @param length the number of bytes to transfer from the array to
     *               virtual memory.
     * @return the number of bytes successfully transferred.
     */
    public int writeVirtualMemory(int vaddr, byte[] data, int offset,
                                  int length) {
        Lib.assertTrue(offset >= 0 && length >= 0 && offset + length <= data.length);

        byte[] memory = Machine.processor().getMemory();

        int page = vaddr / Processor.pageSize;
        int start = vaddr % Processor.pageSize;

        int amount = 0;
        int remain = length;

        while (remain > 0) {

            if (page >= pageTable.length) break;
            if (pageTable[page].readOnly) break;

            int count = Math.min(Processor.pageSize - start, remain);
            int physicalStart = pageTable[page].ppn * Processor.pageSize + start;
            System.arraycopy(data, offset, memory, physicalStart, count);
            amount += count;
            remain -= count;

            page++;
            start = 0;
        }

        return amount;
    }

    /**
     * Load the executable with the specified name into this process, and
     * prepare to pass it the specified arguments. Opens the executable, reads
     * its header information, and copies sections and arguments into this
     * process's virtual memory.
     *
     * @param name the name of the file containing the executable.
     * @param args the arguments to pass to the executable.
     * @return <tt>true</tt> if the executable was successfully loaded.
     */
    private boolean load(String name, String[] args) {
        Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");

        OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
        if (executable == null) {
            Lib.debug(dbgProcess, "\topen failed");
            return false;
        }

        try {
            coff = new Coff(executable);
        } catch (EOFException e) {
            executable.close();
            Lib.debug(dbgProcess, "\tcoff load failed");
            return false;
        }

        // make sure the sections are contiguous and start at page 0
        numPages = 0;
        for (int s = 0; s < coff.getNumSections(); s++) {
            CoffSection section = coff.getSection(s);
            if (section.getFirstVPN() != numPages) {
                coff.close();
                Lib.debug(dbgProcess, "\tfragmented executable");
                return false;
            }
            numPages += section.getLength();
        }

        // make sure the argv array will fit in one page
        byte[][] argv = new byte[args.length][];
        int argsSize = 0;
        for (int i = 0; i < args.length; i++) {
            argv[i] = args[i].getBytes();
            // 4 bytes for argv[] pointer; then string plus one for null byte
            argsSize += 4 + argv[i].length + 1;
        }
        if (argsSize > pageSize) {
            coff.close();
            Lib.debug(dbgProcess, "\targuments too long");
            return false;
        }

        // program counter initially points at the program entry point
        initialPC = coff.getEntryPoint();

        // next comes the stack; stack pointer initially points to top of it
        numPages += stackPages;
        initialSP = numPages * pageSize;

        // and finally reserve 1 page for arguments
        numPages++;

        if (!loadSections())
            return false;

        // store arguments in last page
        int entryOffset = (numPages - 1) * pageSize;
        int stringOffset = entryOffset + args.length * 4;

        this.argc = args.length;
        this.argv = entryOffset;

        for (byte[] bytes : argv) {
            byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
            Lib.assertTrue(writeVirtualMemory(entryOffset, stringOffsetBytes) == 4);
            entryOffset += 4;
            Lib.assertTrue(writeVirtualMemory(stringOffset, bytes) ==
                    bytes.length);
            stringOffset += bytes.length;
            Lib.assertTrue(writeVirtualMemory(stringOffset, new byte[]{0}) == 1);
            stringOffset += 1;
        }

        return true;
    }

    /**
     * Allocates memory for this process, and loads the COFF sections into
     * memory. If this returns successfully, the process will definitely be
     * run (this is the last step in process initialization that can fail).
     *
     * @return <tt>true</tt> if the sections were successfully loaded.
     */
    protected boolean loadSections() {
        if (numPages > Machine.processor().getNumPhysPages()) {
            coff.close();
            Lib.debug(dbgProcess, "\tinsufficient physical memory");
            return false;
        }

        // load sections
        // 统计需要多少页
        int vpc = stackPages + 1;
        for (int i = 0; i < coff.getNumSections(); i++) {
            CoffSection section = coff.getSection(i);
            vpc += section.getLength();
        }
        pageTable = new TranslationEntry[vpc];

        if (!allocPageMemory(pageTable, 0, vpc)) {
            return false;
        }

        for (int s = 0; s < coff.getNumSections(); s++) {
            CoffSection section = coff.getSection(s);

            Lib.debug(dbgProcess, "\tinitializing " + section.getName()
                    + " section (" + section.getLength() + " pages)");

            for (int i = 0; i < section.getLength(); i++) {
                int vpn = section.getFirstVPN() + i;

                pageTable[vpn].readOnly = section.isReadOnly();

                section.loadPage(i, pageTable[vpn].ppn);
            }
        }

        return true;
    }

    private boolean allocPageMemory(TranslationEntry[] pageTable, int offset, int count) {
        Lib.assertTrue(offset + count <= pageTable.length && count > 0);
        boolean success = false;
        boolean intStatus = Machine.interrupt().disable();
        if (UserKernel.freeMemoryPage.size() >= count) {
            success = true;
            for (int i = offset, end = offset + count; i < end; i++) {
                pageTable[i] = new TranslationEntry(i, UserKernel.freeMemoryPage.removeFirst(), true, false, false, false);
            }
        }
        Machine.interrupt().restore(intStatus);
        return success;
    }

    /**
     * Release any resources allocated by <tt>loadSections()</tt>.
     */
    protected void unloadSections() {
        boolean intStatus = Machine.interrupt().disable();
        // 释放内存资源
        for (TranslationEntry entry : pageTable) {
            if (entry != null && entry.valid && entry.ppn != -1) {
                UserKernel.freeMemoryPage.add(entry.ppn);
                entry.valid = false;
            }
        }
        Machine.interrupt().restore(intStatus);
    }

    private void releaseProcessResource() {
        Machine.interrupt().disable();
        // 释放内存资源
        unloadSections();
        // 从表中移除
        userProcessHashMap.remove(id);
        // 清空直接子进程表
        childProcessHashMap.clear();
        // 关闭文件
        for (int i = 0; i < openFiles.length; i++) {
            if (openFiles[i] != null) {
                openFiles[i].close();
                openFiles[i] = null;
            }
        }

        // 关闭可执行文件
        coff.close();

        // 如果这是最后一个进程了，停机
        if (userProcessHashMap.size() == 0) {
            Kernel.kernel.terminate();
        }

        // 终止线程
        KThread.finish();
    }

    /**
     * Initialize the processor's registers in preparation for running the
     * program loaded into this process. Set the PC register to point at the
     * start function, set the stack pointer register to point at the top of
     * the stack, set the A0 and A1 registers to argc and argv, respectively,
     * and initialize all other registers to 0.
     */
    public void initRegisters() {
        Processor processor = Machine.processor();

        // by default, everything's 0
        for (int i = 0; i < processor.numUserRegisters; i++)
            processor.writeRegister(i, 0);

        // initialize PC and SP according
        processor.writeRegister(Processor.regPC, initialPC);
        processor.writeRegister(Processor.regSP, initialSP);

        // initialize the first two argument registers to argc and argv
        processor.writeRegister(Processor.regA0, argc);
        processor.writeRegister(Processor.regA1, argv);
    }

    /**
     * Handle the halt() system call.
     */
    private int handleHalt() {
        if (id != 1) {
//            System.err.println("Process which called halt() is not root process");
            return -1;
        }

        Kernel.kernel.terminate();

        Lib.assertNotReached("Machine.halt() did not halt machine!");
        return 0;
    }


    private static final int
            syscallHalt = 0,
            syscallExit = 1,
            syscallExec = 2,
            syscallJoin = 3,
            syscallCreate = 4,
            syscallOpen = 5,
            syscallRead = 6,
            syscallWrite = 7,
            syscallClose = 8,
            syscallUnlink = 9;

    /**
     * Handle a syscall exception. Called by <tt>handleException()</tt>. The
     * <i>syscall</i> argument identifies which syscall the user executed:
     *
     * <table>
     * <tr><td>syscall#</td><td>syscall prototype</td></tr>
     * <tr><td>0</td><td><tt>void halt();</tt></td></tr>
     * <tr><td>1</td><td><tt>void exit(int status);</tt></td></tr>
     * <tr><td>2</td><td><tt>int  exec(char *name, int argc, char **argv);
     * 								</tt></td></tr>
     * <tr><td>3</td><td><tt>int  join(int pid, int *status);</tt></td></tr>
     * <tr><td>4</td><td><tt>int  creat(char *name);</tt></td></tr>
     * <tr><td>5</td><td><tt>int  open(char *name);</tt></td></tr>
     * <tr><td>6</td><td><tt>int  read(int fd, char *buffer, int size);
     * 								</tt></td></tr>
     * <tr><td>7</td><td><tt>int  write(int fd, char *buffer, int size);
     * 								</tt></td></tr>
     * <tr><td>8</td><td><tt>int  close(int fd);</tt></td></tr>
     * <tr><td>9</td><td><tt>int  unlink(char *name);</tt></td></tr>
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
            case syscallHalt:
                return handleHalt();

            case syscallExit:
                return handleExit(a0);

            case syscallExec:
                return handleExec(a0, a1, a2);

            case syscallJoin:
                return handleJoin(a0, a1);

            case syscallCreate:
                return handleCreate(a0);

            case syscallOpen:
                return handleOpen(a0);

            case syscallRead:
                return handleRead(a0, a1, a2);

            case syscallWrite:
                return handleWrite(a0, a1, a2);

            case syscallClose:
                return handleClose(a0);

            case syscallUnlink:
                return handleUnlink(a0);

            default:
                System.out.println("unknown syscall: " + syscall);
                Lib.debug(dbgProcess, "Unknown syscall " + syscall);
                Lib.assertNotReached("Unknown system call!");
        }
        return 0;
    }

    private int handleExit(int status) {
        exitCode = status;
        error = false;

        releaseProcessResource();
        return 0;
    }

    private int handleExec(int fileAddr, int argc, int argvAddr) {
        // 参数处理
        String file = readVirtualMemoryString(fileAddr, 256);
        if (file == null) return -1;
        String[] args = new String[argc];
        for (int i = 0; i < argc; i++) {
            byte[] charPointer = new byte[4];
            readVirtualMemory(argvAddr + i * 4, charPointer);
            int cp = (charPointer[0] & 0xff) | ((charPointer[1] & 0xff) << 8) | ((charPointer[2] & 0xff) << 16) | ((charPointer[3] & 0xff) << 24);
            args[i] = readVirtualMemoryString(cp, 256);
            if (args[i] == null) return -1;
        }
        // 创建进程
        UserProcess userProcess = newUserProcess();
        userProcess.parentProcess = this;
        if (userProcess.execute(file, args)) {
            // 添加到子进程列表
            childProcessHashMap.put(userProcess.id, userProcess);
            return userProcess.id;
        } else {
            return -1;
        }
    }

    private int handleJoin(int process, int exitAddr) {
        // 从子进程列表中找到进程
        UserProcess userProcess = childProcessHashMap.get(process);
        if (userProcess == null || userProcess.parentProcess != this) return -1;
        // 等待进程执行
        boolean intStatus = Machine.interrupt().disable();
        userProcess.uThread.join();
        Machine.interrupt().restore(intStatus);
        if (exitAddr != 0) {
            // 获取返回代码
            int exitCode = userProcess.exitCode;
            byte[] bytes = new byte[4];
            bytes[0] = (byte) (exitCode & 0xff);
            bytes[1] = (byte) ((exitCode >> 8) & 0xff);
            bytes[2] = (byte) ((exitCode >> 16) & 0xff);
            bytes[3] = (byte) ((exitCode >> 24) & 0xff);
            // 内存写入失败的情况
            if (writeVirtualMemory(exitAddr, bytes) != 4) {
                return -1;
            }
        }
        // 从子进程列表中移除
        childProcessHashMap.remove(process);
        // 返回
        return userProcess.error ? 0 : 1;
    }

    private int handleCreate(int filepathAddr) {
        String filepath = readVirtualMemoryString(filepathAddr, 256);
        if (filepath == null) return -1;
        int index = nextOpenFileIndex();
        if (index == -1) return -1;
        FileSystem fileSystem = Machine.stubFileSystem();
        OpenFile openFile = fileSystem.open(filepath, true);
        if (openFile == null) return -1;
        openFiles[index] = openFile;
        return index;
    }

    private int handleOpen(int filepathAddr) {
        String filepath = readVirtualMemoryString(filepathAddr, 256);
        if (filepath == null) return -1;
        int index = nextOpenFileIndex();
        if (index == -1) return -1;
        FileSystem fileSystem = Machine.stubFileSystem();
        OpenFile openFile = fileSystem.open(filepath, false);
        if (openFile == null) return -1;
        openFiles[index] = openFile;
        return index;
    }

    private int handleRead(int fd, int bufferAddr, int count) {
        if (fd < 0 || fd >= 16) return -1;
        OpenFile openFile = openFiles[fd];
        if (openFile == null) return -1;
        if (count < 0) return -1;
        if (count == 0) return 0;
        byte[] buffer = new byte[count];
        int len = openFile.read(buffer, 0, count);
        writeVirtualMemory(bufferAddr, buffer, 0, len);
        return len;
    }

    private int handleWrite(int fd, int bufferAddr, int count) {
        if (fd < 0 || fd >= 16) return -1;
        OpenFile openFile = openFiles[fd];
        if (openFile == null) return -1;
        if (count < 0) return -1;
        if (count == 0) return 0;
        byte[] buffer = new byte[count];
        int len = readVirtualMemory(bufferAddr, buffer, 0, count);
        return openFile.write(buffer, 0, len);
    }

    private int handleClose(int fd) {
        if (fd < 0 || fd >= openFiles.length) return -1;
        OpenFile openFile = openFiles[fd];
        if (openFile == null) return -1;
        openFile.close();
        openFiles[fd] = null;
        return 0;
    }

    private int handleUnlink(int filepathAddr) {
        String str = readVirtualMemoryString(filepathAddr, 256);
        FileSystem fileSystem = Machine.stubFileSystem();
        return fileSystem.remove(str) ? 0 : -1;
    }

    // 获取下一个可用的保存已打开文件的位置
    private int nextOpenFileIndex() {
        for (int i = 0; i < openFiles.length; i++) {
            if (openFiles[i] == null) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Handle a user exception. Called by
     * <tt>UserKernel.exceptionHandler()</tt>. The
     * <i>cause</i> argument identifies which exception occurred; see the
     * <tt>Processor.exceptionZZZ</tt> constants.
     *
     * @param cause the user exception that occurred.
     */
    public void handleException(int cause) {
        Processor processor = Machine.processor();

        switch (cause) {
            case Processor.exceptionSyscall:
                int result = handleSyscall(processor.readRegister(Processor.regV0),
                        processor.readRegister(Processor.regA0),
                        processor.readRegister(Processor.regA1),
                        processor.readRegister(Processor.regA2),
                        processor.readRegister(Processor.regA3)
                );
                processor.writeRegister(Processor.regV0, result);
                processor.advancePC();
                break;

            default:
                Lib.debug(dbgProcess, "Unexpected exception: " +
                        Processor.exceptionNames[cause]);
                exitCode = cause;
                error = true;
                releaseProcessResource();
        }
    }

    /**
     * The program being run by this process.
     */
    protected Coff coff;

    /**
     * This process's page table.
     */
    protected TranslationEntry[] pageTable;
    /**
     * The number of contiguous pages occupied by the program.
     */
    protected int numPages;

    /**
     * The number of pages in the program's stack.
     */
    protected final int stackPages = 8;

    private int initialPC, initialSP;
    private int argc, argv;

    // 打开文件列表
    private final OpenFile[] openFiles = new OpenFile[16];

    // 父进程
    private UserProcess parentProcess;

    // 进程 id
    private final int id = ++globalId;
    // 全局进程 id
    private static int globalId = 0;
    // 全局进程
    private static final HashMap<Integer, UserProcess> userProcessHashMap = new HashMap<>();
    // 直接子进程
    private final HashMap<Integer, UserProcess> childProcessHashMap = new HashMap<>();

    // 主线程
    private final UThread uThread = new UThread(this);

    // 退出代码
    private int exitCode = -1;
    // 是否因异常退出
    private boolean error;

    private static final int pageSize = Processor.pageSize;
    private static final char dbgProcess = 'a';

}
