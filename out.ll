@.str.1 = constant [6 x i8] c"Haiii\00"
@.str.0 = constant [8 x i8] c"\25s\3a\20\25d\0a\00"

%.struct.0 = type {
	ptr
}

declare void @printf(ptr, ...)

define void @printT(ptr %.0, i32 %.1) {
	%.2 = getelementptr %.struct.0, ptr %.0, i32 0, i32 0
	%.3 = load ptr, ptr %.2
	call void (ptr, ...) @printf(ptr @.str.0, ptr %.3, i32 %.1)
	ret void
}

define i32 @main() {
	%.0 = alloca i32
	%.1 = alloca %.struct.0
	store i32 10, ptr %.0
	%.2 = getelementptr %.struct.0, ptr %.1, i32 0, i32 0
	store ptr @.str.1, ptr %.2
	%.3 = load i32, ptr %.0
	call void (ptr, i32) @printT(ptr %.1, i32 %.3)
	ret i32 0
}