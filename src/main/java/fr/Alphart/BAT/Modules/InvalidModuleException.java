package fr.Alphart.BAT.Modules;

public class InvalidModuleException extends Exception {

	private static final long serialVersionUID = 1L;

	private final String message;

	public InvalidModuleException(final String message){
		this.message = message;
	}

	public static long getSerialversionuid() {
		return serialVersionUID;
	}

	public String getMessage() {
		return message;
	}

}
