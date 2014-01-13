[cljc.repl][]
=============

A REPL for [ClojureC][].

Prerequisites
-------------

After cloning this repository, execute the following command to
download Clojure and ClojureC:

```sh
./bootstrap
```

ClojureC has some additional dependencies that you will have to
install, notably GLib 2, the Boehm-Demers-Weiser garbage collector,
and the Perl Compatible Regular Expressions library:

```sh
aptitude install libglib2.0-dev libgc-dev libpcre3-dev
```

and for Mac:

```sh
brew install glib bdw-gc pcre
```

Usage
-----

Now you are ready to launch the REPL!

```sh
./repl
```

License
-------
Copyright © 2013-2014 [Bert Frees][bert]

Distributed under the Eclipse Public License, the same as Clojure.

[cljc.repl]: http://github.com/bertfrees/cljc.repl
[ClojureC]: https://github.com/schani/clojurec
[bert]: http://github.com/bertfrees
