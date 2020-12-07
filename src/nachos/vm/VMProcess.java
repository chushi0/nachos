package nachos.vm;

import nachos.machine.*;
import nachos.threads.Lock;
import nachos.userprog.UserKernel;
import nachos.userprog.UserProcess;

import java.util.Map;

/**
 * A <tt>UserProcess</tt> that supports demand-paging.
 */
public class VMProcess extends UserProcess {

    private int sectionCount;

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
            TranslationEntry translationEntry = new TranslationEntry(-1, 0, false, false, false, false);
            Machine.processor().writeTLBEntry(i, translationEntry);
        }
    }

    private void rewriteTLBEntry(TranslationEntry entry) {
        int size = Machine.processor().getTLBSize();
        for (int i = 0; i < size; i++) {
            TranslationEntry translationEntry = Machine.processor().readTLBEntry(i);
            if (translationEntry.vpn == entry.vpn) {
                Machine.processor().writeTLBEntry(i, entry);
                return;
            }
        }
    }

    private void updateTLBPageInfo() {
        int size = Machine.processor().getTLBSize();
        for (int i = 0; i < size; i++) {
            TranslationEntry entry = Machine.processor().readTLBEntry(i);
            if (entry.vpn != -1) {
                TranslationEntry target = pageTable[entry.vpn];
                target.used |= entry.used;
                target.dirty |= entry.dirty;
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
        int vpc = 0;
        for (int i = 0; i < coff.getNumSections(); i++) {
            CoffSection section = coff.getSection(i);
            vpc += section.getLength();
        }
        sectionCount = vpc;
        vpc += stackPages + 1;
        pageTable = new TranslationEntry[vpc];

        for (int i = 0; i < vpc; i++) {
            pageTable[i] = new TranslationEntry(-1, 0, false, false, false, false);
        }

        return true;
    }

    // 分配内存
    private void initializeMemory(TranslationEntry entry, int vpn) {
        entry.vpn = vpn;
        entry.ppn = availableMemory();
        entry.valid = true;
        entry.used = false;
        entry.dirty = true;
        entry.readOnly = false;
        if (vpn < sectionCount) {
            int c = vpn;
            for (int s = 0; s < coff.getNumSections(); s++) {
                CoffSection section = coff.getSection(s);

                int length = section.getLength();
                if (length <= c) {
                    c -= length;
                    continue;
                }

                Lib.assertTrue(vpn == section.getFirstVPN() + c);

                entry.readOnly = section.isReadOnly();

                boolean intStatus = Machine.interrupt().disable();
                if (!entry.valid) swapIn(vpn);
                lock.acquire();
                Machine.interrupt().restore(intStatus);
                section.loadPage(c, entry.ppn);
                lock.release();
                break;
            }
        }
        boolean intStatus = Machine.interrupt().disable();
        VMKernel.PVPN pvpn = new VMKernel.PVPN();
        pvpn.pid = id;
        pvpn.vpn = vpn;
        VMKernel.invPage.put(pvpn, entry);
        Machine.interrupt().restore(intStatus);
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
            if (translationEntry.vpn == -1) continue;
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
        updateTLBPageInfo();
        int vpn = vaddr / Processor.pageSize;
        if (vpn < 0 || vpn >= pageTable.length) return false;
        int replace = Lib.random(Machine.processor().getTLBSize());
        for (int i = 0; i < Machine.processor().getTLBSize(); i++) {
            TranslationEntry entry = Machine.processor().readTLBEntry(i);
            if (!entry.valid) {
                replace = i;
                break;
            }
        }
        TranslationEntry entry = pageTable[vpn];
        validMemory(vpn, entry);
        Machine.processor().writeTLBEntry(replace, entry);
        return true;
    }

    @Override
    public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) {
        Lib.assertTrue(offset >= 0 && length >= 0 && offset + length <= data.length);

        byte[] memory = Machine.processor().getMemory();

        int page = vaddr / Processor.pageSize;
        int start = vaddr % Processor.pageSize;

        int amount = 0;
        int remain = length;

        while (remain > 0) {

            if (page >= pageTable.length) break;
            if (pageTable[page].readOnly) break;

            validMemory(page, pageTable[page]);

            int count = Math.min(Processor.pageSize - start, remain);
            int physicalStart = pageTable[page].ppn * Processor.pageSize + start;
            System.arraycopy(data, offset, memory, physicalStart, count);
            amount += count;
            remain -= count;

            pageTable[page].used = true;
            pageTable[page].dirty = true;

            page++;
            start = 0;
        }

        return amount;
    }

    private void validMemory(int page, TranslationEntry entry) {
        if (entry.vpn == -1) {
            initializeMemory(entry, page);
        }
        if (!entry.valid) {
            swapIn(page);
        }
    }

    @Override
    public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) {
        Lib.assertTrue(offset >= 0 && length >= 0 && offset + length <= data.length);

        byte[] memory = Machine.processor().getMemory();

        int page = vaddr / Processor.pageSize;
        int start = vaddr % Processor.pageSize;

        int amount = 0;
        int remain = length;

        while (remain > 0) {

            if (page >= pageTable.length) break;

            validMemory(page, pageTable[page]);

            int count = Math.min(Processor.pageSize - start, remain);
            int physicalStart = pageTable[page].ppn * Processor.pageSize + start;
            System.arraycopy(memory, physicalStart, data, offset, count);
            amount += count;
            remain -= count;

            pageTable[page].used = true;

            page++;
            start = 0;
        }

        return amount;
    }

    @Override
    protected int handleSbrk(int sizeAddr) {
        byte[] buf = new byte[4];
        readVirtualMemory(sizeAddr, buf);
        // 希望分配的内存大小
        int hopeSize = (buf[0] & 0xff) | ((buf[1] & 0xff) << 8) | ((buf[2] & 0xff) << 16) | ((buf[3] & 0xff) << 24);
        if (hopeSize <= 0) return 0;
        // 页数
        int pageSize = hopeSize / Processor.pageSize + 1;
        int startVaddr = pageTable.length * Processor.pageSize;
        TranslationEntry[] newPageTable = new TranslationEntry[pageTable.length + pageSize];
        if (allocPageMemory(newPageTable, pageTable.length, pageSize)) {
            System.arraycopy(pageTable, 0, newPageTable, 0, pageTable.length);
            pageTable = newPageTable;
            int size = pageSize * Processor.pageSize;
            buf[0] = (byte) (size & 0xff);
            buf[1] = (byte) ((size >> 8) & 0xff);
            buf[2] = (byte) ((size >> 16) & 0xff);
            buf[3] = (byte) ((size >> 24) & 0xff);
            writeVirtualMemory(sizeAddr, buf);
            return startVaddr;
        }
        return 0;
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
        updateTLBPageInfo();
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
            rewriteTLBEntry(entry);
            Machine.interrupt().restore(intStatus);
            return entry.ppn;
        }
        // 未写过的页
        if (notDirtyPage != null) {
            TranslationEntry entry = VMKernel.invPage.get(notDirtyPage);
            VMKernel.invPage.remove(notDirtyPage);
            entry.valid = false;
            rewriteTLBEntry(entry);
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
        rewriteTLBEntry(entry);
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
        int vmp = VMKernel.vmMap.get(pvpn);
        byte[] memory = Machine.processor().getMemory();
        lock.acquire();
        Machine.interrupt().enable();
        VMKernel.vmFile.seek(vmp * Processor.pageSize);
        VMKernel.vmFile.read(memory, entry.ppn * Processor.pageSize, Processor.pageSize);
        Machine.interrupt().disable();
        entry.valid = true;
        entry.used = false;
        entry.dirty = false;
        VMKernel.invPage.put(pvpn, entry);
        Machine.interrupt().restore(intStatus);
        lock.release();
    }

    // 调页锁
    private static final Lock lock = new Lock();

    private static final int pageSize = Processor.pageSize;
    private static final char dbgProcess = 'a';
    private static final char dbgVM = 'v';
}
