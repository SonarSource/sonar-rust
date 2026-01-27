#!/usr/bin/env python3
import tree_sitter
import tree_sitter_rust
import os
import glob
import pathlib
import subprocess
import re
import requests
import json
import shutil
import time
import argparse

MAX_TRIES = 4
CLIPPY_RULE_URL = 'https://rust-lang.github.io/rust-clippy/master/index.html';
RUST_LANGUAGE = tree_sitter.Language(tree_sitter_rust.language())
GLEAN_URL = 'https://sonarsource-be.glean.com/rest/api/v1/chat'

INITIAL_PROMPT = """
You are a coding assistant for a Rust developer. Your task is to explain Clippy lints to a developer in a more advanced format than the original Clippy rule description.
For each Clippy lint, you will receive the following data:
[Clippy lint key]
[Clippy rule description]
For each Clippy lint, you will have to provide the following information in the following format. Include the headers in square brackets in the output.
Do not format the output in any way. Do not include any additional information.
[Clippy lint key]
[Rule name]
A descriptive name for the rule, for example "Use of unwrap should be avoided"
[Issue message]
An issue message that is short and actionable, for example "Remove this unwrap call and handle the error".
[Why is this an issue]
A concise description of the rule and explanation of why it is an issue
[Noncompliant code example]
Exactly one, noncompliant code example. The offending line should have a `// Noncompliant` comment in the end. The code must be compilable.
[Compliant code example]
Exactly one compliant code example.  The code must be compilable.
[End]

You will receive an example in the next prompt, then the first Clippy lint.
"""

EXAMPLE_USER = """
[Clippy lint key]
vec_resize_to_zero
[Clippy rule description]
### What it does
Finds occurrences of `Vec::resize(0, an_int)`

### Why is this bad?
This is probably an argument inversion mistake.

### Example
```no_run
vec![1, 2, 3, 4, 5].resize(0, 5)
```

Use instead:
```no_run
vec![1, 2, 3, 4, 5].clear()
```
"""

EXAMPLE_AI = """
[Clippy lint key]
vec_resize_to_zero
[Rule name]
Avoid resizing a vector to zero using `vec.resize(0, value)`
[Issue message]
Replace `vec.resize(0, value)` with `vec.clear()`, or swap the `vec.resize` arguments.
[Why is this an issue]
Resizing a vector to zero using `vec.resize(0, value)` is misleading. It's either unreadable if the intent was simply to clear the vector, making the code harder to understand, or suspicious and unintentional if a resize was actually expected, but the arguments were accidentally swapped.
[Noncompliant code example]
let mut vec = vec![1, 2, 3, 4, 5];
vec.resize(0, 5); // Noncompliant: Resizing the vector to 0.
[Compliant code example]
let mut vec = vec![1, 2, 3, 4, 5];
vec.clear(); // Compliant: Clear the vector.
[End]
"""

CARGO_TOML = """
[package]
name = "clippy-lints-test"
version = "0.1.0"
edition = "2021"
"""

def collect_clippy_lints_files(clippy_dir: str):
    lint_files = []
    for file in glob.glob("**/*.rs", recursive=True, root_dir=clippy_dir):
        lint_files.append(pathlib.Path(clippy_dir) / file)

    return lint_files

def collect_lints_from_ast(node: tree_sitter.Node, lints):
    if node.type == "macro_invocation":
        macro_name = node.child_by_field_name("macro").text
        if macro_name == b"declare_clippy_lint":
            lint = parse_lint(node)
            if lint:
                lints.append(lint)
        return
    
    for i in range(0, node.child_count):
        collect_lints_from_ast(node.child(i), lints)

def collect_clippy_lints(clippy_dir: str):
    lints = []

    for file in collect_clippy_lints_files(clippy_dir):
        with open(file, encoding="utf-8") as fp:
            code = fp.read()
            parser = tree_sitter.Parser(RUST_LANGUAGE)
            tree = parser.parse(bytes(code, "utf8"))

            collect_lints_from_ast(tree.root_node, lints)

    sorted(lints, key=lambda x: x['key'])

    return lints

def parse_lint(macro):
    token_tree = macro.child(2)
    if token_tree.type != 'token_tree':
        print(f"Error: Unexpected token tree type: {token_tree.type}")
        return

    pub_token_idx = -1
    token_idx = 0
    while token_idx < token_tree.child_count:
        token = token_tree.children[token_idx]
        if token.text == b'pub':
            pub_token_idx = token_idx
            break
        token_idx += 1

    if pub_token_idx == -1:
        print('Error: Could not find `pub` token in Clippy lint declaration')
        return

    category = token_tree.children[pub_token_idx + 3].text
    if category == b'internal':
        return None

    key = token_tree.children[pub_token_idx + 1].text.lower().decode('utf-8')
    url = f"{CLIPPY_RULE_URL}#{key}"

    # Collect all line_comment children to create the rule description
    description = ''
    for i in range(0, token_tree.child_count):
        child = token_tree.child(i)
        if child.type == 'line_comment':
            description_text = child.text.decode('utf-8').strip('//').strip()
            description += description_text + '\n'

    return {'key': key, 'name': key, 'url': url, 'description': description, 'category': category.decode('utf-8')}



def glean_chat(message, glean_token):
    headers = {
        'Content-Type': 'application/json',
        'Authorization': f'Bearer {glean_token}'
    }

    data = {
        'stream': False,
        'messages': []
    }

    prompts = [
        # Send the initial prompt with the task
        {
            'author': 'USER',
            'messageType': 'CONTENT',
            'fragments': [{'text': INITIAL_PROMPT}]
        },
        # Send an example user message with a Clippy lint
        {
            'author': 'USER',
            'messageType': 'CONTENT',
            'fragments': [{'text': EXAMPLE_USER}]
        },
        # Send the message expected from the AI for the user example
        {
            'author': 'GLEAN_AI',
            'messageType': 'CONTENT',
            'fragments': [{'text': EXAMPLE_AI}]
        },
        # Send the message regarding the current clippy lint
        {
            'author': 'USER',
            'messageType': 'CONTENT',
            'fragments': [{'text': message}]
        }
    ]

    # Glean messages are processed in reverse order, i.e. the first message is the latest
    prompts.reverse()
    data['messages'] = prompts

    try:
        print(f'Sending request...')
        with requests.post(GLEAN_URL, headers=headers, json=data, stream=False) as response:
            if response.status_code == 200:
                resp_json = json.loads(response.text)
                for message in resp_json['messages']:
                    if message['messageType'] == 'CONTENT' and message['author'] == 'GLEAN_AI':
                        text = ''
                        for fragment in message['fragments']:
                            text += fragment.get('text', '')
                        return text
            else:
                print(f'Status code: {response.status_code}, error: {response.text}')
                exit(1)
    except requests.exceptions.RequestException as e:
        print(f'Request Exception: {str(e)}')
        exit(1)


def process_response(response_text: str):
    # The response is in the format:
    #   [Section 1]
    #   Text 1
    #   [Section 2]
    #   Text 2
    # Each section header enclosed in square brackets is a key, and the text following it is the value
    lines = response_text.split('\n')
    response = {}
    response['raw response'] = response_text
    current_key = None
    current_value = ''
    for line in lines:
        if line.startswith('['):
            if current_key:
                response[current_key] = current_value
            current_key = line.strip().strip('[]')
            current_value = ''
        else:
            current_value += line + '\n'
    response[current_key] = current_value.strip()
    return response


def is_top_level_function(code):
    return code.startswith('fn')


def run_code_example(code, key, kind, out_dir):
    """Sets up a temporary Rust project with the given code and runs clippy on it"""
    temp_dir = f'{out_dir}/examples/{kind}'
    os.makedirs(temp_dir, exist_ok=True)

    # Set up Cargo.toml
    cargo_toml_path = os.path.join(temp_dir, 'Cargo.toml')
    with open(cargo_toml_path, 'w') as f:
        f.write(CARGO_TOML)

    main_rs_path = os.path.join(temp_dir, 'src', 'main.rs')
    os.makedirs(os.path.dirname(main_rs_path), exist_ok=True)
    with open(main_rs_path, 'w') as f:
        # Some code examples may not be top-level functions, so we need to wrap them in a main function
        if is_top_level_function(code):
            f.write(f'#[allow(dead_code, unused_variables)]\n{code}\n fn main() {{}}')
        else:
            f.write(f'#[allow(dead_code, unused_variables)]\nfn main() {{\n {code}\n }}')
    
    ret = subprocess.run(['cargo', 'clippy',  '--', '-A', 'clippy::all', '-W', f'clippy::{key}'], cwd=temp_dir, capture_output=True)
    
    out = ret.stderr.decode('utf-8')

    return out

def write_file(file_path, content):
    with open(file_path, 'w') as f:
        f.write(content)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description='Transform Clippy lints into Sonar RSPEC format.')
    parser.add_argument('--glean-token', type=str, help='Glean token. If not supplied, the GLEAN_TOKEN environment variable will be used.')
    parser.add_argument('--out-dir', '-o', type=str, help='Output directory for the transformed lints. Default is `out` in the current working directory.')
    parser.add_argument('--lint-categories', '-c', type=str, help='Comma-separated list of lint categories to process, e.g. `correctness`. Default is all categories.')
    parser.add_argument('clippy_dir', type=str, help='Path to the Clippy lints directory, e.g. /path/to/rust-clippy/clippy_lints/src.')
    
    args = parser.parse_args()

    glean_token = args.glean_token or os.environ.get('GLEAN_TOKEN')
    if not glean_token:
        print('Error: Glean token not supplied')
        exit(1)

    categories = args.lint_categories.split(',') if args.lint_categories else None
    clippy_lints = collect_clippy_lints(clippy_dir=args.clippy_dir)
   
    summary = {}
    rules = {}

    out_dir = args.out_dir or 'out'
    shutil.rmtree(out_dir, ignore_errors=True)
    os.makedirs(out_dir, exist_ok=True)


    processed_responses = {}
    prompt_clippy_lints = []

    queue = [lint for lint in clippy_lints]
    tries = {lint['key']: 0 for lint in clippy_lints }

    while queue:
        lint = queue.pop()
        clippy_key = lint['key']

        tries[clippy_key] = tries[clippy_key] + 1
        if categories is not None:
            if lint['category'] not in categories:
                continue

        # Glean API tends to rate limit requests very early, so we need to add some delay
        time.sleep(0.5)

        print("Processing lint: ", lint['key'])
        r = glean_chat(f"[Clippy lint key]\n{lint['key']}\n[Clippy rule description]\n{lint['description']}\n", glean_token=glean_token)
        
        resp = process_response(r)

        try:
            lint_output_dir = f'{out_dir}/{clippy_key}'
            os.makedirs(f'{lint_output_dir}', exist_ok=True)
            write_file(f'{lint_output_dir}/response.txt', resp['raw response'])
            write_file(f'{lint_output_dir}/clippy.txt', lint['description'])

            # Despite being asked not to provide any output formatting, Glean AI tends to enclose code examples in '```'
            noncompliant_example = re.sub(r'```([a-zA-Z0-9-_]+)?', '', resp['Noncompliant code example']).strip()
            compliant_example = re.sub(r'```([a-zA-Z0-9-_]+)?', '', resp['Compliant code example']).strip()

            nc_res = run_code_example(noncompliant_example, resp['Clippy lint key'].strip(), kind='noncompliant', out_dir=lint_output_dir)
            c_res = run_code_example(compliant_example, resp['Clippy lint key'].strip(), kind='compliant', out_dir=lint_output_dir)

            write_file(f'{lint_output_dir}/noncompliant.txt', nc_res)
            write_file(f'{lint_output_dir}/compliant.txt', c_res)
            write_file(f'{lint_output_dir}/rule.adoc', f"""
== Why is this an issue?
{resp['Why is this an issue']}

=== Code examples

==== Noncompliant code example
[source,rust,diff-id=1,diff-type=noncompliant]
----
{noncompliant_example}
----

==== Compliant solution

[source,rust,diff-id=1,diff-type=compliant]
----
{compliant_example}
----

== Resources
=== Documentation

* Clippy Lints - https://rust-lang.github.io/rust-clippy/master/index.html#{clippy_key}
""")

            summary[clippy_key] = {
                'status': 'success',
                'clippy_category': lint['category'],
                'processed_response': resp,
                'message': resp['Issue message']
            }
            rules[clippy_key] = {
                'message': resp['Issue message'],
            }
            print('----\n\n')
        except Exception as e:
            # Exceptions are mostly due to some request error or a KeyError due to the LLM not returning the response in the expected structure.
            # Our strategy here is to retry the request a few times before giving up.
            print(f'[{clippy_key}] Exception occured: {repr(e)}')
            summary[clippy_key] = {
                'status': 'error',
                'error': repr(e),
                'processed_response': resp
            }
            # Try again
            if tries[clippy_key] < MAX_TRIES:
                queue.append(lint)
            continue

    write_file(f'{out_dir}/summary.json', json.dumps(summary, indent=2))
    write_file(f'{out_dir}/rules.json', json.dumps(rules, indent=2))
