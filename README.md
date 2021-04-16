smallpng
===

This is a small (less than a thousand lines of code) single-file Java library for writing and reading PNG files directly from and to a ByteBuffer containing RGBA pixel values.

It supports everything described in the PNG standard and can automatically reduce the color depth or the images when it is possible, thus for some types of images generating smaller files than the built-in `ImageIO`.

## API

```java
package afdw.smallpng;

public class SmallPNG {
    public static ByteBuffer read(InputStream inputStream,
                                  AtomicInteger outWidth,
                                  AtomicInteger outHeight) throws IOException;

    public static void write(OutputStream outputStream,
                             ByteBuffer image,
                             int width,
                             int height) throws IOException;
}
```

Here `AtomicInteger`s are used as output parameters.

## License

This project is licensed under either of

* The Unlicense ([LICENSE-UNLICENSE](LICENSE-UNLICENSE) or
  https://unlicense.org/)
* MIT license ([LICENSE-MIT](LICENSE-MIT) or
  http://opensource.org/licenses/MIT)

at your option.
