package com.fastbase;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FastBaseApplication {

    static {
        // Hadoop sur Windows exige HADOOP_HOME ou hadoop.home.dir.
        // On pointe vers java.io.tmpdir si aucun n'est défini — suffit pour les I/O Parquet locales.
        if (System.getenv("HADOOP_HOME") == null && System.getProperty("hadoop.home.dir") == null)
            System.setProperty("hadoop.home.dir", System.getProperty("java.io.tmpdir"));
    }

	public static void main(String[] args) {
		SpringApplication.run(FastBaseApplication.class, args);
	}

}
