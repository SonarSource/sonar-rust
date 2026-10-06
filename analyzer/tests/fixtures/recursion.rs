/*
 * SonarQube Rust Plugin
 * Copyright (C) SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * You can redistribute and/or modify this program under the terms of
 * the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */
#![allow(dead_code, unused_imports)]

mod engine {
    pub struct Engine;

    pub trait Run {
        fn run(&self, remaining: u32) -> u32;
    }

    impl Engine {
        pub fn start(&self, remaining: u32) -> u32 {
            if remaining == 0 {
                0
            } else {
                <Self as Run>::run(self, remaining - 1)
            }
        }
    }

    impl Run for Engine {
        fn run(&self, remaining: u32) -> u32 {
            if remaining == 0 {
                0
            } else {
                super::step(self, remaining - 1)
            }
        }
    }
}

use engine::{Engine as Machine, Run as _};

fn step(machine: &Machine, remaining: u32) -> u32 {
    if remaining == 0 {
        0
    } else {
        machine.start(remaining - 1)
    }
}

fn entry() -> u32 {
    Machine.start(5)
}
