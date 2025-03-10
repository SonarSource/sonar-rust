pub fn sign(value: i32) -> &'static str {
    if value > 0 {
        "positive"
    } else if value < 0 {
        "negative"
    } else {
        "zero"
    }
}
