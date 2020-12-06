#include "syscall.h"

/**
 * 内存块控制数据结构，用于管理所有的内存块
 */
typedef struct {
	// 是否可用
	int is_available;
	// 大小
	int size;
} mem_control_block;

// 全局变量：进程的堆底
char* __malloc_mem_start = 0;
// 全局变量：进程的堆顶
char* __malloc_mem_end = 0;

char* malloc(int size) {
	// 用于返回的地址
	char* location = 0;
	// 计算实际需要的内存大小
	size += sizeof(mem_control_block);

	// 如果堆底是0，则向操作系统申请内存，并设置堆底
	if(__malloc_mem_start == 0) {
		int dword = size;
		location = sbrk(&dword);
		if(location == 0) return 0;
		__malloc_mem_start = location;
		// 额外分配的量
		int additional = dword - size;
		// 如果额外分配的量大于两倍内存块大小，则预留此内存
		if(additional > sizeof(mem_control_block) * 2) {
			mem_control_block *mcb = (mem_control_block*) location;
			mcb->is_available = 0;
			mcb->size = size;
			mcb = (mem_control_block*) (location + size);
			mcb->is_available = 1;
			mcb->size = additional;
			__malloc_mem_end = (char*)mcb;
		} else {
			mem_control_block *mcb = (mem_control_block*) location;
			mcb->is_available = 0;
			mcb->size = dword;
			__malloc_mem_end = __malloc_mem_start;
		}
		return location + sizeof(mem_control_block);
	}

	// 循环搜索
	mem_control_block *mcb = (mem_control_block *)__malloc_mem_start;
	while((int)mcb <= (int)__malloc_mem_end) {
		// 如果可用并且内存足够
		if(mcb->is_available && mcb->size > size) {
            location = (char*) mcb;
            // 额外分配的量
            int additional = mcb->size - size;
            // 如果额外分配的量大于两倍内存块大小，则预留此内存
            if(additional > sizeof(mem_control_block) * 2) {
                mcb = (mem_control_block*) location;
                mcb->is_available = 0;
                mcb->size = size;
                mcb = (mem_control_block*) (location + size);
                mcb->is_available = 1;
                mcb->size = additional;
                if(location == __malloc_mem_end) {
                    __malloc_mem_end = (char*)mcb;
                }
            } else {
                mcb = (mem_control_block*) location;
                mcb->is_available = 0;
            }
            return location + sizeof(mem_control_block);
		}
		mcb = (mem_control_block*) ((char*)mcb + mcb->size);
	}
	// 空间不够，需要向操作系统申请内存
	int dword = size;
    location = sbrk(&dword);
    if(location == 0) return 0;
    // 检查最后一个内存块是否可用
    mcb = (mem_control_block*)__malloc_mem_end;
    if(mcb->is_available) {
        // 可用，则将新的内存添加到此内存块
        location = __malloc_mem_end;
        mcb->size += dword;
    } else {
        // 不可用，新的内存块是最后一个内存块
        mcb = (mem_control_block*)location;
        mcb->is_available = 1;
        mcb->size = dword;
        __malloc_mem_end = location;
    }
    // 分配最后一个内存块
    // 额外分配的量
    int additional = mcb->size - size;
    // 如果额外分配的量大于两倍内存块大小，则预留此内存
    if(additional > sizeof(mem_control_block) * 2) {
        mcb = (mem_control_block*) location;
        mcb->is_available = 0;
        mcb->size = size;
        mcb = (mem_control_block*) (location + size);
        mcb->is_available = 1;
        mcb->size = additional;
        __malloc_mem_end = (char*)mcb;
    } else {
        mcb = (mem_control_block*) location;
        mcb->is_available = 0;
    }
    return location + sizeof(mem_control_block);
}

void free(char* mem) {
	// 将此内存块标为可用
	mem_control_block* mcb = (mem_control_block*) (mem - sizeof(mem_control_block));
	mcb->is_available = 1;
	// 合并空闲的堆，方便下次申请内存
	mcb = __malloc_mem_start;
	mem_control_block* last = 0;
	while((int)mcb <= (int)__malloc_mem_end) {
		if(last && last->is_available && mcb->is_available) {
			last->size += mcb->size;
			if(mcb == __malloc_mem_end) {
				__malloc_mem_end = last;
				break;
			}
		} else {
			last = mcb;
		}
        mcb = (mem_control_block*) ((char*)mcb + mcb->size);
    }
}