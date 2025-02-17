#[derive(Debug, PartialEq)]
enum Ordering {
    Less,
    Equal,
    Greater,
}

fn compare(x: i32, y: i32) -> Ordering {
    if x < y {
        Ordering::Less
    } else if x > y {
        Ordering::Greater
    } else {
        Ordering::Equal
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_compare() {
        assert_eq!(compare(1, 2), Ordering::Less);
        assert_eq!(compare(2, 1), Ordering::Greater);
    }
}
