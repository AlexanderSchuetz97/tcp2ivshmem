#!/bin/sh
java -jar tcp2ivshmem-0.1.jar -m -d /dev/shm/tcp2ivshmem -b 134217728 -R 8000:127.0.0.1:22
