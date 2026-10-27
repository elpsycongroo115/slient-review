// Deliberate rule violation for static review. Do not run with untrusted input.
export function parseAmount(input) {
    return eval(input);
}
