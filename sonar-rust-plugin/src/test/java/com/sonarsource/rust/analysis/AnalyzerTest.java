package com.sonarsource.rust.analysis;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

class AnalyzerTest {

    @Test
    void analyze() {
      Analyzer analyzer = new Analyzer();
      var result = analyzer.analyze("fn main() { println!(\"Hello, world!\"); }");
      System.out.println(result);
    }
}
