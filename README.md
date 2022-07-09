# shadow-grove

`shadow-grove` is a combination of pieces required to build performant DOM-driven user interfaces, scaling from small to very large. Written in ClojureScript with no extra dependencies. The core pieces are

- `shadow.arborist`: Core Abstraction to create and update DOM trees
- `shadow.grove.db`: Normalized simplistic DB, using just CLJS maps
- `shadow.grove.events`: Event system to handle changes to the system
- `shadow.grove.components`: The component system providing the basis to connect the pieces

## Quickstart

The core structures in `shadow.grove` are modular so each piece needs to be setup separately. You only initialize what you need when you need it. The minimum we need to create is the database and the runtime holding out our other "state". I recommend creating this in a dedicated namespace 

