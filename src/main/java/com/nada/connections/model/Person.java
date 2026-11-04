package com.nada.connections.model;

// A person is a vertex (node) in the graph. The id is a random 8-character hex string (32 random bits).
public record Person(String id, String name) {
}
