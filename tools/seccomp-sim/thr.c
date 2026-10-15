#include <pthread.h>
#include <stdio.h>
#include <sys/wait.h>
#include <unistd.h>
static void *run(void *a) { return a; }
int main(void) {
    pthread_t t[4];
    for (int i = 0; i < 4; i++) pthread_create(&t[i], 0, run, 0);
    for (int i = 0; i < 4; i++) pthread_join(t[i], 0);
    pid_t p = fork();
    if (p == 0) _exit(0);
    waitpid(p, 0, 0);
    printf("musl-threads-ok\n");
    return 0;
}
