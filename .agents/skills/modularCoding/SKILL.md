---
name: modular-coding
description: Enforces strict functional decomposition and the orchestrator pattern. Use when writing, generating, or refactoring functions and methods to guarantee high modularity, small atomic steps, and single-responsibility code.
---

# Modular Coding Skill

Apply this skill whenever writing, modifying, or refactoring code to ensure maximum modularity and readability.

## Core Rule: The Orchestrator Pattern

High-level functions must never contain low-level procedural logic or inline algorithmic operations. They must strictly act as orchestrators that compose atomic sub-functions:

    func(x, y, ...) {
        f1(x);
        f2(y);
        return f3(x, y);
    }

## Modularity Rules

1. **Orchestrator-Only Main Functions:**
   - The main function only tells the story of the flow.
   - Prohibited: Mixing branching logic, complex calculations, or data parsing inside the orchestrator.
   - Max length: Typically under 15 lines.

2. **Atomic Single-Responsibility Sub-Functions:**
   - Every helper function (f1, f2, ...) must perform exactly one isolated, verifiable task.
   - Functions should be short (ideally 5 to 15 lines).
   - Functions must have semantic, intent-revealing names (validateInput(), parsePayload(), dispatchAction()).

3. **Pure and Composable Operations:**
   - Favor pure functions: take inputs, return outputs, avoid hidden side effects.
   - Pass explicit parameters instead of relying on mutable shared state.

4. **Flatten Control Flow:**
   - Eliminate deeply nested if/else chains and giant switch blocks.
   - Replace complex branch bodies with dedicated delegate functions or strategy maps.

## Generation Checklist

Before providing any code, verify:
- [ ] Does the entry-point method merely orchestrate sub-steps?
- [ ] Can each sub-function be unit-tested in isolation?
- [ ] Does every function do exactly one thing?
- [ ] Are all inline loops, parsing steps, and validations extracted into dedicated helpers?