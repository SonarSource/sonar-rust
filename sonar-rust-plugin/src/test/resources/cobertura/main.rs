mod abs;
mod sign;
mod fizzbuzz;

fn main() {
    println!("Hello, world!");
}

#[cfg(test)]
mod tests {

    use crate::abs::abs;
    use crate::sign::sign;
    use crate::fizzbuzz::fizz_buzz;

    #[test]
    fn test_abs() {
        assert_eq!(abs(-5), 5);
    }

    #[test]
    fn test_sign() {
        assert_eq!(sign(2), "positive");
        assert_eq!(sign(0), "zero");
    }

    #[test]
    fn test_fizz_buzz() {
        assert_eq!(fizz_buzz(3), "fizz");
        assert_eq!(fizz_buzz(5), "buzz");
    }

}
