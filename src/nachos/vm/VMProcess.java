package nachos.vm;

import nachos.machine.Lib;
import nachos.machine.Machine;
import nachos.machine.Processor;
import nachos.machine.TranslationEntry;
import nachos.threads.Lock;
import nachos.userprog.UserKernel;
import nachos.userprog.UserProcess;

import java.util.Map;

/**
 * A <tt>UserProcess</tt> that supports demand-paging.
 */
public class VMProcess extends UserProcess {
    /**
     * Allocate a new process.
     */
    public VMProcess() {
        super();
    }

    /**
     * Save the state of this process in preparation for a context switch.
     * Called by <tt>UThread.saveState()</tt>.
     */
    public void saveState() {
        super.saveState();
    }

    /**
     * Restore the state of this process after a context switch. Called by
     * <tt>UThread.restoreState()</tt>.
     */
    public void restoreState() {
        int size = Machine.processor().getTLBSize();
        for (int i = 0; i < size; i++) {
            if (pageTable[i].valid) {
                Machine.processor().writeTLBEntry(i, pageTable[i]);
            } else {
                TranslationEntry translationEntry = new TranslationEntry(0, 0, false, false, false, false);
                Machine.processor().writeTLBEntry(i, translationEntry);
            }
        }
    }

    /**
     * Initializes page tables for this process so that the executable can be
     * demand-paged.
     *
     * @return <tt>true</tt> if successful.
     */
    protected boolean loadSections() {
        return super.loadSections();
    }

    /**
     * Release any resources allocated by <tt>loadSections()</tt>.
     */
    protected void unloadSections() {
    }

    @Override
    protected void freeMemory() {
        boolean intStatus = Machine.interrupt().disable();
        for (TranslationEntry translationEntry : pageTable) {
            VMKernel.PVPN pvpn = new VMKernel.PVPN();
            pvpn.pid = id;
            pvpn.vpn = translationEntry.vpn;
            // 释放物理内存
            VMKernel.invPage.remove(pvpn);
            if (translationEntry.valid) {
                UserKernel.freeMemoryPage.add(translationEntry.ppn);
            }
            // 释放虚拟内存
            int id = VMKernel.vmMap.getOrDefault(pvpn, -1);
            if (id != -1) {
                VMKernel.vmMap.remove(pvpn);
                VMKernel.freeVMBlock.add(id);
            }
        }
        Machine.interrupt().restore(intStatus);
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
            case Processor.exceptionTLBMiss:
                if (handleTLBMiss(Machine.processor().readRegister(Processor.regBadVAddr))) {
                    break;
                }

            default:
                super.handleException(cause);
                break;
        }
    }

    private boolean handleTLBMiss(int vaddr) {
        int vpn = vaddr / Processor.pageSize;
        if (vpn < 0 || vpn >= pageTable.length) return false;
        int replace = Lib.random(Machine.processor().getTLBSize());
        TranslationEntry entry = pageTable[vpn];
        if (!entry.valid) {
            swapIn(vpn);
        }
        Machine.processor().writeTLBEntry(replace, entry);
        return true;
    }

    @Override
    protected boolean allocPageMemory(TranslationEntry[] pageTable, int offset, int count) {
        Lib.assertTrue(offset + count <= pageTable.length && count > 0);
        boolean intStatus = Machine.interrupt().disable();
        for (int i = offset, end = offset + count; i < end; i++) {
            pageTable[i] = new TranslationEntry(i, availableMemory(), true, false, true, true);
            VMKernel.PVPN pvpn = new VMKernel.PVPN();
            pvpn.pid = id;
            pvpn.vpn = i;
            VMKernel.invPage.put(pvpn, pageTable[i]);
        }
        Machine.interrupt().restore(intStatus);
        return true;
    }

    @Override
    public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) {
        return super.writeVirtualMemory(vaddr, data, offset, length);
    }

    @Override
    public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) {
        return super.readVirtualMemory(vaddr, data, offset, length);
    }

    // 获取可用的内存物理页
    // 如果不存在，则将一页换出到虚拟内存
    private int availableMemory() {
        boolean intStatus = Machine.interrupt().disable();
        int ppn;
        if (UserKernel.freeMemoryPage.isEmpty()) {
            ppn = swapOut();
        } else {
            ppn = UserKernel.freeMemoryPage.removeFirst();
        }
        Machine.interrupt().restore(intStatus);
        return ppn;
    }

    private void writeToVM(VMKernel.PVPN pvpn, int ppn) {
        byte[] memory = Machine.processor().getMemory();
        int id = VMKernel.vmMap.getOrDefault(pvpn, -1);
        if (id == -1) {
            if (VMKernel.freeVMBlock.isEmpty()) {
                id = VMKernel.vmBlockLength++;
            } else {
                id = VMKernel.freeVMBlock.removeFirst();
            }
            VMKernel.vmMap.put(pvpn, id);
        }
        lock.acquire();
        boolean intStatus = Machine.interrupt().enabled();
        VMKernel.vmFile.seek(id * Processor.pageSize);
        VMKernel.vmFile.write(memory, ppn * Processor.pageSize, Processor.pageSize);
        Machine.interrupt().restore(intStatus);
        lock.release();
    }

    // 将一页换出到虚拟内存
    // 然后返回它的物理页
    private int swapOut() {
        boolean intStatus = Machine.interrupt().disable();
        VMKernel.PVPN notUsedPage = null;
        VMKernel.PVPN notDirtyPage = null;
        VMKernel.PVPN lastPage = null;
        for (Map.Entry<VMKernel.PVPN, TranslationEntry> entryEntry : VMKernel.invPage.entrySet()) {
            lastPage = entryEntry.getKey();
            TranslationEntry entry = entryEntry.getValue();
            if (!entry.used) {
                notUsedPage = lastPage;
                if (!entry.dirty) {
                    break;
                }
            }
            if (!entry.dirty) {
                notDirtyPage = lastPage;
            }
        }
        // 未使用过的页
        if (notUsedPage != null) {
            TranslationEntry entry = VMKernel.invPage.get(notUsedPage);
            if (entry.dirty) {
                writeToVM(notUsedPage, entry.ppn);
            }
            VMKernel.invPage.remove(notUsedPage);
            entry.valid = false;
            Machine.interrupt().restore(intStatus);
            return entry.ppn;
        }
        // 未写过的页
        if (notDirtyPage != null) {
            TranslationEntry entry = VMKernel.invPage.get(notDirtyPage);
            VMKernel.invPage.remove(notDirtyPage);
            entry.valid = false;
            Machine.interrupt().restore(intStatus);
            return entry.ppn;
        }
        // 都处理过，清除使用标记位，使用最后一个页
        for (Map.Entry<VMKernel.PVPN, TranslationEntry> entryEntry : VMKernel.invPage.entrySet()) {
            TranslationEntry entry = entryEntry.getValue();
            entry.used = false;
        }
        TranslationEntry entry = VMKernel.invPage.get(lastPage);
        writeToVM(lastPage, entry.ppn);
        VMKernel.invPage.remove(lastPage);
        entry.valid = false;
        Machine.interrupt().restore(intStatus);
        return entry.ppn;
    }

    // 将一页从虚拟内存载入物理内存
    private void swapIn(int vpn) {
        boolean intStatus = Machine.interrupt().disable();
        VMKernel.PVPN pvpn = new VMKernel.PVPN();
        pvpn.pid = id;
        pvpn.vpn = vpn;
        TranslationEntry entry = pageTable[vpn];
        entry.ppn = availableMemory();
        int vmp;
        try {
            vmp = VMKernel.vmMap.get(pvpn);
        } catch (NullPointerException e) {
            throw e;
        }
        byte[] memory = Machine.processor().getMemory();
        lock.acquire();
        Machine.interrupt().enable();
        VMKernel.vmFile.seek(vmp * Processor.pageSize);
        VMKernel.vmFile.read(memory, entry.ppn * Processor.pageSize, Processor.pageSize);
        Machine.interrupt().restore(intStatus);
        entry.valid = true;
        entry.used = false;
        entry.dirty = false;
        lock.release();
    }

    // 调页锁
    private static final Lock lock = new Lock();

    private static final int pageSize = Processor.pageSize;
    private static final char dbgProcess = 'a';
    private static final char dbgVM = 'v';
}
