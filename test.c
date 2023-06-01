#include <stdio.h>
#include <stdlib.h>

void test(int size, int nums[]) {
	for(int i = 0; i < size; i++) {
		printf("%d\n", nums[i]);
	}
}
int main() {
	int nums[3] = { 5, 2, 3 };
	test(3, nums);
	return 0;
}
