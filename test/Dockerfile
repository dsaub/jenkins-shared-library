# Imagen mínima para test/Jenkinsfile: el build falla si no se lanza con --ulimit core=0.
FROM busybox:1.37
RUN test "$(ulimit -c)" = "0" || { echo "ulimit -c es $(ulimit -c): falta --ulimit core=0" >&2; exit 1; }
ARG MLIB_TEST_MESSAGE=sin-mensaje
RUN echo "$MLIB_TEST_MESSAGE" > /mlib-test.txt && uname -m >> /mlib-test.txt && cat /mlib-test.txt
