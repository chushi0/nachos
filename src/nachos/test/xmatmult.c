/* matmult.c
 *    Test program to do matrix multiplication on large arrays.
 *
 *    Intended to stress virtual memory system. Should return 7920200 if Dim==200
 */

#include "syscall.h"

#define Dim 	200

int** A;
int** B;
int** C;

void mallocArrays(int*** out) {
	int i;
	*out = (int**) malloc(Dim * sizeof(int*));
	for(i = 0; i < Dim; i++) {
		(*out)[i] = (int*) malloc(Dim * sizeof(int));
	}
}

int
main()
{
	mallocArrays(&A);
	mallocArrays(&B);
	mallocArrays(&C);

    int i, j, k;

    for (i = 0; i < Dim; i++)		/* first initialize the matrices */
	for (j = 0; j < Dim; j++) {
	     A[i][j] = i;
	     B[i][j] = j;
	     C[i][j] = 0;
	}

    for (i = 0; i < Dim; i++)		/* then multiply them together */
	for (j = 0; j < Dim; j++)
            for (k = 0; k < Dim; k++)
		 C[i][j] += A[i][k] * B[k][j];

    printf("C[%d][%d] = %d\n", Dim-1, Dim-1, C[Dim-1][Dim-1]);
    return (C[Dim-1][Dim-1]);		/* and then we're done */
}
