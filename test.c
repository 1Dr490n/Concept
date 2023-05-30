#include <stdio.h>

struct T {
	char* name;	
};

void printT(struct T* t, int a) {
	printf("%s: %d\n", t->name, a);
}


int main() {
	int a = 10;
	struct T test;
	test.name = "Haiii";

	printT(&test, a);

	return 0;
}
