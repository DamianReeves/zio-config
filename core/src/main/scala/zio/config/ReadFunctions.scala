package zio.config

import zio.{ IO, ZIO }
import zio.config.ConfigDescriptor.Sequence
import zio.config.ReadFunctions.ConfResult, ConfResult._
import zio.config.ReadFunctions._
import ReadFunctions.ConfResult
import zio.config.ReadError._

private[config] trait ReadFunctions {
  final def read[K, V, A](
    configuration: ConfigDescriptor[K, V, A]
  ): IO[ReadErrorsVector[K], A] = {
    def loop[V1, B](
      configuration: ConfigDescriptor[K, V1, B],
      paths: Vector[K]
    ): ZIO[Any, Nothing, ::[ConfResult[Vector[K], ::[B]]]] =
      configuration match {
        case ConfigDescriptor.Source(path, source: ConfigSource[K, V1], propertyType: PropertyType[V1, B]) =>
          for {
            result <- source
                       .getConfigValue(paths :+ path)
                       .either
                       .map({
                         case Left(error) =>
                           singleton(errors(error)): ::[ConfResult[Vector[K], ::[B]]]
                         case Right(opt) =>
                           opt match {
                             case Some(values) =>
                               mapCons(withIndex(values.value)) {
                                 case (Some(values), _) =>
                                   seqEitherCons(
                                     mapCons(values)(value => propertyType.read(value))
                                   ).fold(
                                     r =>
                                       errors(
                                         ReadError.ParseError(
                                           paths :+ path,
                                           ReadFunctions.parseErrorMessage(r.value.toString, r.typeInfo)
                                         )
                                       ),
                                     e => Exists[Vector[K], ::[B]](paths :+ path, e)
                                   )
                                 case (None, id) =>
                                   Errors(ReadError.MissingValue[Vector[K]](paths :+ path, Some(id)))
                               }

                             case None =>
                               singleton(
                                 errors(ReadError.MissingValue[Vector[K]](paths :+ path, None)): ConfResult[
                                   Vector[K],
                                   ::[B]
                                 ]
                               )

                           }
                       })
          } yield result

        case s: Sequence[K, V1, B] @unchecked =>
          val Sequence(config) = s
          loop(config, paths).map(
            result =>
              mapCons(result)(each => {
                partitionFailures(each) match {
                  case Left(value)  => Exists(paths, singleton(value))
                  case Right(value) => Errors(value.error)
                }
              })
          )

        case ConfigDescriptor.Nested(path, c) =>
          loop(c, paths :+ path)

        case cd: ConfigDescriptor.XmapEither[K, V1, a, B] =>
          val ConfigDescriptor.XmapEither(c, f, _) = cd
          loop(c, paths).flatMap { results =>
            foreach(results)(a => {
              ZIO.succeed {
                val h = a.flatMap(
                  paths,
                  values =>
                    seqEitherCons(mapCons(values)(f)) match {
                      case Left(value) =>
                        Right(Errors(ReadError.Unknown[Vector[K]](paths, new RuntimeException(value))))
                      case Right(value) => Left(value)
                    }
                )
                h
              }
            })
          }

        case cd: ConfigDescriptor.Default[K, V1, B] =>
          val ConfigDescriptor.Default(config, value) = cd
          loop(config, paths).map(result => mapCons(result)(_.default(paths, singleton(value))))

        case ConfigDescriptor.Describe(c, _) =>
          loop(c, paths)

        case cd: ConfigDescriptor.Optional[K, V1, B] @unchecked =>
          val ConfigDescriptor.Optional(c) = cd
          loop(c, paths).map({ results =>
            mapCons(results)(_.map(res => mapCons(res)(each => Option(each))).default(paths, singleton(None)))
          })

        case r: ConfigDescriptor.Zip[K, V1, a, b] @unchecked => {
          val ConfigDescriptor.Zip(left, right) = r
          for {
            res1 <- loop(left, paths)
            res2 <- loop(right, paths)
            result3 = (seqConfResult(res1), seqConfResult(res2)) match {
              case (Exists(path, v1), Exists(_, v2)) =>
                Exists(path, ::(v1.flatten.zip(v2.flatten).head, v1.flatten.zip(v2.flatten).tail)): ConfResult[Vector[
                  K
                ], ::[(a, b)]]
              case (Exists(_, _), Errors(error)) => Errors(error): ConfResult[Vector[K], ::[(a, b)]]
              case (Exists(_, v1), Seqq(list)) =>
                (partitionFailures(Seqq(list)) match {
                  case Left(vvv) =>
                    Exists(paths, ::(v1.flatten.zip(vvv.flatten).head, v1.flatten.zip(vvv.flatten).tail)): ConfResult[
                      Vector[K],
                      ::[(a, b)]
                    ]
                  case Right(value) => Errors(value.error): ConfResult[Vector[K], ::[(a, b)]]
                }): ConfResult[Vector[K], ::[(a, b)]]

              case (Seqq(list), Seqq(list2)) =>
                Seqq(mapCons(zipCons(list, list2))({
                  case (Left(v1s), Left(v2s)) =>
                    Left(::(v1s.flatten.zip(v2s.flatten).head, v1s.flatten.zip(v2s.flatten).tail))
                  case (Left(_), Right(v2s))    => Right(v2s)
                  case (Right(v1s), Left(_))    => Right(v1s)
                  case (Right(v1s), Right(v2s)) => Right(Errors(AndErrors(v1s.error, v2s.error)))
                }))

              case (Errors(r1), Errors(r2))   => Errors(AndErrors(r1, r2))
              case (Errors(r1), Exists(_, _)) => Errors(r1)
              case (Errors(r1), Seqq(_))      => Errors(r1)
              case (Seqq(r1), Errors(r2)) =>
                val errors = Seqq(r1).getErrors
                if (errors.nonEmpty) {
                  Errors(AndErrors(errors.head, r2))
                } else
                  Errors(r2)

              case (Seqq(list), Exists(_, v1)) =>
                (partitionFailures(Seqq(list)) match {
                  case Left(vvv) =>
                    Exists(paths, ::(vvv.flatten.zip(v1.flatten).head, vvv.flatten.zip(v1.flatten).tail)): ConfResult[
                      Vector[K],
                      ::[(a, b)]
                    ]
                  case Right(value) => Errors(value.error): ConfResult[Vector[K], ::[(a, b)]]
                }): ConfResult[Vector[K], ::[(a, b)]]
            }
          } yield singleton(result3)
        }

        case cd: ConfigDescriptor.OrElseEither[K, V1, a, b] @unchecked =>
          val ConfigDescriptor.OrElseEither(left, right) = cd

          for {
            res1 <- loop(left, paths)
            res2 <- loop(right, paths)
            result3 = mapCons(zipCons(res1, res2)) {
              case (Exists(path, v1), _) =>
                Exists(path, mapCons(v1)(Left(_))): ConfResult[Vector[K], ::[Either[a, b]]]

              case (Seqq(v1), Exists(_, v2)) =>
                Seqq(mapCons(withIndex(v1))({
                  case (value, _) =>
                    value match {
                      case Left(value) => Left(mapCons(value)(Left(_))): Either[::[Either[a, b]], Errors[Vector[K]]]
                      case Right(_)    => Left(mapCons(v2)(Right(_))): Either[::[Either[a, b]], Errors[Vector[K]]]
                    }
                }))

              case (Seqq(v1), Errors(v2)) =>
                Seqq(mapCons(withIndex(v1))({
                  case (value, _) =>
                    value match {
                      case Left(value) => Left(mapCons(value)(Left(_))): Either[::[Either[a, b]], Errors[Vector[K]]]
                      case Right(_)    => Right(Errors(v2)): Either[::[Either[a, b]], Errors[Vector[K]]]
                    }
                }))

              case (Seqq(v1), Seqq(v2)) =>
                Seqq(mapCons(withIndex(v1))({
                  case (value, id) =>
                    value match {
                      case Left(value) => Left(mapCons(value)(Left(_))): Either[::[Either[a, b]], Errors[Vector[K]]]
                      case Right(errors1) =>
                        v2.lift(id) match {
                          case Some(value) =>
                            value match {
                              case Left(value) =>
                                Left(mapCons(value)(Right(_))): Either[::[Either[a, b]], Errors[Vector[K]]]
                              case Right(errors2) =>
                                Right(Errors(OrErrors(errors1.error, errors2.error))): Either[::[Either[a, b]], Errors[
                                  Vector[K]
                                ]]
                            }
                          case None =>
                            Right(Errors(OrErrors(errors1.error, ReadError.MissingValue(paths, Some(id))))): Either[::[
                              Either[a, b]
                            ], Errors[Vector[K]]]
                        }
                    }
                }))

              case (Errors(_), Exists(path, v1)) =>
                Exists(path, mapCons(v1)(Right(_))): ConfResult[Vector[K], ::[Either[a, b]]]

              case (Errors(r1), Errors(r2)) =>
                Errors(OrErrors(r1, r2)): ConfResult[Vector[K], ::[Either[a, b]]]

              case (Errors(r1), Seqq(result)) =>
                Seqq(mapCons(result) {
                  case Left(value) => Left(mapCons(value)(a => Right(a))): Either[::[Either[a, b]], Errors[Vector[K]]]
                  case Right(value) =>
                    Right(Errors(OrErrors(r1, value.error))): Either[::[Either[a, b]], Errors[Vector[K]]]
                })
            }
          } yield result3

        case ConfigDescriptor.OrElse(left, right) =>
          for {
            res1 <- loop(left, paths)
            res2 <- loop(right, paths)
            result3 = mapCons(zipCons(res1, res2)) {
              case (Exists(path, v1), _) =>
                Exists(path, v1): ConfResult[Vector[K], ::[B]]

              case (Seqq(v1), Exists(_, v2)) =>
                Seqq(mapCons(withIndex(v1))({
                  case (value, _) =>
                    value match {
                      case Left(value) => Left(value): Either[::[B], Errors[Vector[K]]]
                      case Right(_)    => Left(v2): Either[::[B], Errors[Vector[K]]]
                    }
                }))

              case (Seqq(v1), Errors(v2)) =>
                Seqq(mapCons(withIndex(v1))({
                  case (value, _) =>
                    value match {
                      case Left(value) => Left(value): Either[::[B], Errors[Vector[K]]]
                      case Right(_)    => Right(Errors(v2)): Either[::[B], Errors[Vector[K]]]
                    }
                }))

              case (Seqq(v1), Seqq(v2)) =>
                Seqq(mapCons(withIndex(v1))({
                  case (value, id) =>
                    value match {
                      case Left(value) => Left(value): Either[::[B], Errors[Vector[K]]]
                      case Right(errors1) =>
                        v2.lift(id) match {
                          case Some(value) =>
                            value match {
                              case Left(value) =>
                                Left(value): Either[::[B], Errors[Vector[K]]]
                              case Right(errors2) =>
                                Right(Errors(OrErrors(errors1.error, errors2.error))): Either[::[B], Errors[Vector[K]]]
                            }
                          case None =>
                            Right(Errors(OrErrors(errors1.error, ReadError.MissingValue(paths, Some(id))))): Either[::[
                              B
                            ], Errors[Vector[K]]]
                        }
                    }
                }))

              case (Errors(_), Exists(path, v1)) =>
                Exists(path, v1): ConfResult[Vector[K], ::[B]]

              case (Errors(r1), Errors(r2)) =>
                Errors(OrErrors(r1, r2)): ConfResult[Vector[K], ::[B]]

              case (Errors(r1), Seqq(result)) =>
                Seqq(mapCons(result) {
                  case Left(value)  => Left(value): Either[::[B], Errors[Vector[K]]]
                  case Right(value) => Right(Errors(OrErrors(r1, value.error))): Either[::[B], Errors[Vector[K]]]

                })
            }
          } yield result3
      }

    loop(configuration, Vector.empty[K]).flatMap(values => {
      val errors = mapCons(values)(_.getErrors).flatten

      if (errors.nonEmpty)
        IO.fail(::(errors.head, errors.tail))
      else {
        val result = mapCons(values) {
          case Exists(_, v) =>
            List(v)
          case Errors(_) => Nil
          case Seqq(list) =>
            list.flatMap({
              case Left(value) => List(value)
              case Right(_)    => Nil
            })
        }
        IO.succeed(result.flatten.head.head)
      }
    })
  }
}

object ReadFunctions {
  def parseErrorMessage(given: String, expectedType: String) =
    s"Provided value is ${given.toString}, expecting the type ${expectedType}"

  sealed trait ConfResult[K, +B] { self =>
    def map[C](f: B => C): ConfResult[K, C] = self match {
      case Exists(path, v) => Exists(path, f(v))
      case Errors(errors)  => Errors(errors)
      case Seqq(list) =>
        Seqq(mapCons(list)({
          case Left(value)  => Left(f(value))
          case Right(value) => Right(value)
        }))
    }

    def flatMap[C](paths: K, f: B => Either[C, Errors[K]]): ConfResult[K, C] = self match {
      case Exists(_, v) =>
        f(v) match {
          case Left(value)  => Exists(paths, value)
          case Right(value) => Errors(value.error)
        }
      case Errors(error) => Errors(error)
      case Seqq(result) =>
        Seqq(mapCons(result)({
          case Left(value)  => f(value)
          case Right(value) => Right(Errors(value.error))
        }))
    }

    def getErrors: List[ReadError[K]] =
      self match {
        case Exists(_, _)  => List()
        case Errors(error) => List(error)
        case Seqq(list) =>
          list.flatMap({
            case Left(_)      => Nil
            case Right(value) => List(value.error)
          })
      }

    def default[B1 >: B, K1 >: K](path: K1, value: B1): ConfResult[K1, B1] = self match {
      case Exists(path, v) => Exists(path, v)
      case Errors(error) =>
        if (hasNonFatalErrors(error) || hasParseErrors(error)) Errors(error) else Exists(path, value)
      case Seqq(result) =>
        Seqq(mapCons(result)({
          case Left(value) => Left(value): Either[B1, Errors[K1]]
          case Right(_)    => Left(value): Either[B1, Errors[K1]]
        }))
    }
  }

  object ConfResult {

    case class Exists[K, B](path: K, v: B) extends ConfResult[K, B]

    case class Errors[K](error: ReadError[K]) extends ConfResult[K, Nothing]

    case class Seqq[K, B](list: ::[Either[B, Errors[K]]]) extends ConfResult[K, B]

    // Will be called from sequence
    // The idea here is a bit tricky
    // After sequencing an operation, we get either an accumulated values in {{{ Exists }}}
    // or a seqq which consist of values and errors.
    // When applying default/option, ideally, all the errors in seqq is converted to optional or default
    // the next step is to accumulate them into Exists. Even after this, if errors occur fail them
    // else continue.
    def partitionFailures[K, B](conf: ConfResult[K, ::[B]]): Either[::[B], Errors[K]] = {
      val r = conf match {
        case Exists(_, v)  => Left(v)
        case Errors(error) => Right(Errors(error))
        case Seqq(list) =>
          list.tail.foldLeft(list.head)(
            (acc, a) =>
              (acc, a) match {
                case (Left(value), Left(value2)) => Left(addCons(value, value2))
                case (Right(value), Right(value2)) =>
                  Right(Errors(AndErrors(value2.error, value.error))): Either[::[B], Errors[K]]
                case (Left(_), Right(value2)) => Right(value2)
                case (Right(errirs), Left(_)) => Right(errirs)
              }
          )
      }
      r

    }

    def errors[K](error: ReadError[K]): ConfResult[K, Nothing] =
      Errors(error)

    // After applying option we need to collate those values

    def seqConfResult[K, B](values: ::[ConfResult[K, B]]): ConfResult[K, ::[B]] = {
      val reversed = values.reverse
      val result = reversed.tail.foldLeft(reversed.head.map(singleton))(
        (b, a) =>
          a match {
            case Exists(_, aa) =>
              b match {
                case Exists(path, bb) =>
                  Exists(path, ::(aa, bb))

                case Errors(error) =>
                  val output = Seqq(::(Left(singleton(aa)), singleton(Right(Errors(error)))))
                  output

                case Seqq(results) =>
                  Seqq(::(Left(singleton(aa)), results))
              }

            case Errors(error1) =>
              b match {
                case value @ Exists(_, _) =>
                  Seqq(::(Right(Errors(error1)), singleton(Left(value.v))))

                case Errors(error2) =>
                  Errors(AndErrors(error1, error2))

                case Seqq(results) =>
                  Seqq(::(Right(Errors(error1)), results))
              }

            case Seqq(result1) =>
              val result1Seqq = mapCons(result1) {
                case Left(value)  => Left(singleton(value))
                case Right(value) => Right(value)
              }
              b match {
                case a @ Exists(_, _) =>
                  Seqq(::(Left(a.v), result1Seqq))
                case a @ Errors(_) =>
                  Seqq(::(Right(a), result1Seqq))
                case Seqq(result2) =>
                  val ress = result1Seqq ++ result2
                  Seqq(::(ress.head, ress.tail))
              }
          }
      )

      result
    }

  }

  final def hasNonFatalErrors[K, V1, B](value: ReadError[K]): Boolean =
    value match {
      case ReadError.MissingValue(_, _) => false
      case ReadError.ParseError(_, _)   => false
      case ReadError.Unknown(_, _)      => true
      case ReadError.OrErrors(leftErrors, rightErrors) =>
        hasNonFatalErrors[K, V1, B](leftErrors) || hasNonFatalErrors[K, V1, B](rightErrors)
      case ReadError.AndErrors(leftErrors, rightErrors) =>
        hasNonFatalErrors[K, V1, B](leftErrors) || hasNonFatalErrors[K, V1, B](rightErrors)
    }

  final def hasParseErrors[K, V1, B](value: ReadError[K]): Boolean =
    value match {
      case ReadError.MissingValue(_, _) => false
      case ReadError.ParseError(_, _)   => true
      case ReadError.Unknown(_, _)      => true
      case ReadError.OrErrors(leftErrors, rightErrors) =>
        hasParseErrors[K, V1, B](leftErrors) || hasParseErrors[K, V1, B](rightErrors)
      case ReadError.AndErrors(leftErrors, rightErrors) =>
        hasParseErrors[K, V1, B](leftErrors) || hasParseErrors[K, V1, B](rightErrors)
    }
}
