#include "syscall.h"
#include "stdlib.h"
#include "stdio.h"

int main() {
	int res;
	char* m;
	int* intArr;
	int i;
	printf("try to create 10k in stack memory: ");
	res = join(exec("subtest.coff", 0, 0), 0);
	if(res == 0) {
		printf("fail.\n");
	} else {
		printf("success.\n");
	}
	printf("try to create 10k in heap memory: ");
	m = malloc(10240);
	if(m == 0) {
		printf("fail.\n");
		return 0;
	}
	printf("success. address: %d\n", m);
	printf("try to write memory...");
	intArr = (int*) m;
	for(i = 0; i < 10240 / 4; i++) {
		intArr[i] = i;
	}
	printf("and then check memory...");
	for(i = 0; i < 10240 / 4; i++) {
    	if(intArr[i] != i) {
    	    printf("fail.\n");
    	    return 0;
    	}
    }
    printf("success.\n");
    printf("free memory...");
    free(m);
    printf("and then alloc 1k...");
    m = malloc(1024);
    memset(m, 0, 1024);
    printf("result = %d\nand another 1k...", m);
    char *m2 = malloc(1024);
    printf("result = %d\nfree these memory...", m2);
    free(m2);
    free(m);
    printf("\n");
    return 0;
}

