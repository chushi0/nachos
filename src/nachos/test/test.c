#include "syscall.h"
#include "stdlib.h"
#include "stdio.h"

int main() {
	char *file = "subtest.coff";
	char *par1 = "subtest";
	char *par2 = "param";
	char *param[] = { par1, par2 };
	printf("%s\n", "root hello!");
	int res = exec(file, 2, param);
	printf("res: %d\n", res);
//	printf("res: %d\n", res);
//	join(res, 0);
//	halt();
	return 0;
}

