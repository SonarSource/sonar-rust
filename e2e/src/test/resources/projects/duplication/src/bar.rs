fn bar(results: Vec<&str>) {
  println!("a");
  println!("b");
  println!("c");
  println!("d");
  println!("e");
  if results.len() > 10 {
      println!("f");
      println!("g");
      println!("h");
      println!("i");
      println!("j");
      for result in results {
          println!("{}", result);
          println!("k");
          println!("l");
          println!("m");
          println!("n");
      }
      println!("o");
      println!("p");
      println!("q");
      println!("r");
      println!("s");
      println!("t");
  }
  println!("u");
  println!("v");
  println!("w");
  println!("x");
}
