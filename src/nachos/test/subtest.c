#include "syscall.h"
#include "stdlib.h"
#include "stdio.h"

int main(int argc, char** argv) {
//	assert(argc == 2);
	printf("subtest: argc=%d\n", argc);
	printf("argv=%s\n", argv[1]);
//	printf("%s\n", "subprocess hello");
//	printf("%d\n", argc);
//	printf("hello world");
	return 0;
}