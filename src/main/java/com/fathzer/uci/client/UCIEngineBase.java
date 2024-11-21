package com.fathzer.uci.client;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.fathzer.uci.client.GoParameters.TimeControl;
import com.fathzer.uci.client.Option.Type;

import java.io.Closeable;

class UCIEngineBase implements Closeable {
	private static final String ID_NAME_PREFIX = "id name ";
	private static final String CHESS960_OPTION = "UCI_Chess960";
	private static final String PONDER_OPTION = "Ponder";
	
	private final BufferedReader reader;
	private final InterruptibleReplyListener interruptibleReader;
	private final BufferedWriter writer;
	private List<Option<?>> options;
	private String name;
	private boolean is960Supported;
	private boolean whiteToPlay;
	private boolean positionSet;

	protected UCIEngineBase(BufferedReader reader, BufferedWriter writer) {
		this.reader = reader;
		this.interruptibleReader = new InterruptibleReplyListener(this::write, this::blockingRead);
		this.writer = writer;
	}
	
	private void checkInit() {
		if (options==null) {
			throw new IllegalStateException("Init was not called");
		}
	}
	
	void init() throws IOException {
		final List<String> optionsLines = new LinkedList<>();
		waitAnswer("uci", "uciok"::equals, line -> {
			if (line.startsWith(ID_NAME_PREFIX)) {
				name = line.substring(ID_NAME_PREFIX.length());
			} else {
				optionsLines.add(line);
			}
		});
		options = new LinkedList<>();
		for (String line:optionsLines) {
			final Optional<Option<?>> ooption = parseOption(line);
			if (ooption.isPresent() && isOptionSupported(ooption.get())) {
				options.add(ooption.get());
			}
		}
	}
	
	private boolean isOptionSupported(Option<?> option) {
		checkInit();
		if (CHESS960_OPTION.equals(option.getName())) {
			is960Supported = true;
		} else if (!PONDER_OPTION.equals(option.getName())) {
			//TODO Ponder is not supported yet
			option.addListener((prev, cur) -> {
				try {
					setOption(option, cur);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
			return true;
		}
		return false;
	}
	
	private Optional<Option<?>> parseOption(String line) throws IOException {
		try {
			return OptionParser.parse(line);
		} catch (IllegalArgumentException e) {
			throw new IOException(e);
		}
	}
	
	private void setOption(Option<?> option, Object value) throws IOException {
		final StringBuilder buf = new StringBuilder("setoption name ");
		buf.append(option.getName());
		if (Type.BUTTON!=option.getType()) {
			buf.append(" value ");
			buf.append(value);
		}
		write(buf.toString());
	}
	
	protected void write(String line) throws IOException {
		this.writer.write(line);
		this.writer.newLine();
		this.writer.flush();
	}
	
	//FIXME Make this method private and call method before and after reading the internal reader
	/** Reads the internal blocking reader until a line is available.
	 * <br>WARNING: Never call this method outside this class except while overriding this method (for instance, to add logs to it).
	 * @return The line that was read
	 * @throws IOException If something went wrong
	 */
	protected String blockingRead() throws IOException {
		return reader.readLine();
	}

	public List<Option<?>> getOptions() {
		checkInit();
		return options;
	}
	
	public boolean isSupported(Variant variant) {
		checkInit();
		return variant==Variant.STANDARD || (variant==Variant.CHESS960 && is960Supported);
	}

	public boolean newGame(Variant variant) throws IOException {
		checkInit();
		positionSet = false;
		if (variant==Variant.CHESS960 && !is960Supported) {
			return false;
		}
		write("ucinewgame");
		if (is960Supported) {
			write("setoption name "+CHESS960_OPTION + " value "+(variant==Variant.CHESS960));
		}
		return waitAnswer("isready", "readyok"::equals)!=null;
	}

	/** Reads the engine standard output until a valid answer is returned.
	 * @param answerValidator a predicate that checks the lines returned by engine. 
	 * @return The line that is considered valid, null if no valid line is returned
	 * and the engine closed its output.
	 * @throws IOException If communication with engine fails
	 */
	private String waitAnswer(String command, Predicate<String> answerValidator) throws IOException {
		return waitAnswer(command, answerValidator, s->{});
	}
	/** Reads the engine standard output until a valid answer is returned.
	 * @param answerValidator a predicate that checks the lines returned by engine. 
	 * @return The line that is considered valid, null if no valid line is returned
	 * and the engine closed its output.
	 * @throws IOException If communication with engine fails
	 */
	private String waitAnswer(String command, Predicate<String> answerValidator, Consumer<String> otherLines) throws IOException {
		return interruptibleReader.waitAnswer(command, answerValidator, otherLines);
	}

	public void setPosition(Optional<String> fen, List<String> moves) throws IOException {
		checkInit();
		whiteToPlay = fen.isEmpty() || "w".equals(fen.get().split(" ")[1]);
		if (moves.size()%2!=0) {
			whiteToPlay = !whiteToPlay;
		}
		final StringBuilder builder = new StringBuilder(fen.isEmpty() ? "position startpos" : "position fen "+fen.get());
		if (!moves.isEmpty()) {
			builder.append(" moves");
			for (String move : moves) {
				builder.append(" ");
				builder.append(move);
			}
		}
		write(builder.toString());
		positionSet = true;
	}

	public GoReply go(GoParameters params) throws IOException {
		checkInit();
		if (!positionSet) {
			throw new IllegalStateException("No position defined");
		}
		final GoReplyParser parser = new GoReplyParser();
		return parser.get(waitAnswer(getGoCommand(params), GoReplyParser.IS_REPLY, parser::parseInfo));
	}

	private String getGoCommand(GoParameters params) {
		final StringBuilder command = new StringBuilder("go");
		if (params.getTimeControl()!=null) {
			final TimeControl clock = params.getTimeControl();
			final char prefix = whiteToPlay ? 'w' : 'b';
			command.append(' ').append(prefix).append("time ").append(clock.remainingMs());
			if (clock.incrementMs()!=0) {
				command.append(' ').append(prefix).append("inc ").append(clock.incrementMs());
			}
			if (clock.movesToGo()!=0) {
				command.append(' ').append("movestogo ").append(clock.movesToGo());
			}
		}
		if (params.isPonder()) {
			command.append(' ').append("ponder");
		}
		//TODO Other parameters
		return command.toString();
	}
	
	public void stop() throws IOException {
		this.write("stop");
	}

	public void close() throws IOException {
		this.write("quit");
		closeStreams();
	}

	public void closeStreams() throws IOException {
		interruptibleReader.close();
		this.reader.close();
		this.writer.close();
	}

	public String getName() {
		return name==null ? "?" : name;
	}
}
