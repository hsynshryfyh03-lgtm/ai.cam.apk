package com.sentinel.eye;

// خدمة تعمل بصلاحيات shell عبر Shizuku (UserService).
interface IShellService {
    // رقم المعاملة 16777114 محجوز لدى Shizuku لإيقاف الخدمة.
    void destroy() = 16777114;

    // ينفّذ أمراً في shell ويعيد "exit=N" ثم المخرجات.
    String exec(String command) = 1;
}
