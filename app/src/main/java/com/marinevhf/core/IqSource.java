package com.marinevhf.core;

import java.io.Closeable;
import java.io.IOException;

/** Источник IQ-отсчётов: rtl_tcp-драйвер на Android, файл или симулятор в тестах. */
public interface IqSource extends Closeable {
    void setSampleRate(int hz) throws IOException;

    void setCenterFreq(long hz) throws IOException;

    void setGainAuto() throws IOException;

    void setGainDb(double db) throws IOException;

    void setPpm(int ppm) throws IOException;

    /** Выбросить накопленные в буфере устаревшие данные (после перестройки). */
    void flush() throws IOException;

    /** Читает ровно re.length комплексных отсчётов, нормированных в [-1, 1). */
    void read(float[] re, float[] im) throws IOException;

    String info();
}
