# Convenience targets. Everything here is a thin wrapper around a command you could type
# yourself; nothing is hidden behind them.

SHELL := /bin/bash
URL   ?= http://localhost:8080

.PHONY: help up down logs test burst burst-local image

help:           ## Show this help
	@grep -E '^[a-z-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-12s\033[0m %s\n", $$1, $$2}'

up:             ## Start the service and a database with docker compose
	docker compose up --build

down:           ## Stop everything and remove the database volume
	docker compose down -v

logs:           ## Follow the application logs
	docker compose logs -f app

test:           ## Run the full test suite (Testcontainers; needs a Docker daemon)
	./mvnw test 2>/dev/null || mvn test

burst:          ## Fire the stampede at URL=... (default: localhost:8080)
	./burst.sh $(URL)

burst-local:    ## Fire the stampede at a locally running service
	./burst.sh http://localhost:8080

image:          ## Build the container image only
	docker compose build
