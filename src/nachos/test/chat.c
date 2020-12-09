#include "syscall.h"
#include "stdlib.c"
#include "stdio.c"

int main(int argc, char** argv) {
	if(argc == 1) {
		printf("Usage: char <server-ip>\n");
		return 1;
	}
	int ip = atoi(argv[1]);
	int file = connect(ip, 15);
	printf("connected!\n");

}