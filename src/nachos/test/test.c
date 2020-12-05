#include "syscall.h"
#include "stdlib.h"
#include "stdio.h"

int main() {
	printf("This process will end by throw an exception.\n");
	int a = 0;
	int b = 0;
	a /= b;
	return 0;
}

