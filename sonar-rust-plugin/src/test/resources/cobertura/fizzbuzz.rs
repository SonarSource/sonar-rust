pub fn fizz_buzz(n: i32) -> String {
    let mut r = String::new();
    if n % 3 == 0 {
        r.push_str("fizz");
    }
    if n % 5 == 0 {
        r.push_str("buzz");
    }

    if n % 3 != 0 && n % 5 != 0 {
        return format!("{}", n);
    }

    return r;
}
