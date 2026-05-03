rootProject.name = "example-otel-java"

include(":order-service", ":payment-service")

project(":order-service").projectDir = file("services/order-service")
project(":payment-service").projectDir = file("services/payment-service")
